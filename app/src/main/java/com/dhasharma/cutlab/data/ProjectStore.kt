package com.dhasharma.cutlab.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.dhasharma.cutlab.model.*
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Blocking disk/media work: callers must invoke this store on Dispatchers.IO. */
class ProjectStore(private val context: Context) {
    private val root = File(context.filesDir,"projects").also { it.mkdirs() }
    private fun directory(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid project identifier" }
        return ProjectFiles.safeChild(root,id)
    }
    fun list(): List<Project> = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith("import-") }
        .mapNotNull { runCatching { load(it.name) }.getOrNull() }.sortedByDescending { it.modifiedAt }
    fun save(project: Project) = ProjectFiles.save(directory(project.id),project)
    fun load(id: String): Project = ProjectFiles.load(directory(id))
    fun delete(id: String) { check(directory(id).deleteRecursively()) { "Could not delete project" } }

    fun importMedia(projectId: String, uri: Uri): MediaAsset {
        val name = context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "Imported media"
        val mediaDir = File(directory(projectId),"media").also { it.mkdirs() }
        val id = newId()
        val extension = name.substringAfterLast('.',"").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "media"
        val target = File(mediaDir,"$id.$extension")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Media cannot be opened")
            input.use { source -> FileOutputStream(target).use { output ->
                val buf = ByteArray(65536)
                var total = 0L
                while (true) { val n = source.read(buf); if (n < 0) break; total += n; require(total <= 16L*1024*1024*1024) { "Media exceeds 16 GB import limit" }; output.write(buf,0,n) }
                output.fd.sync()
            } }
            return inspect(target,id,name)
        } catch (error: Throwable) { target.delete(); throw error }
    }

    private fun inspect(file: File, id: String, name: String): MediaAsset {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path,bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            require(bounds.outMimeType in setOf("image/jpeg","image/png")) { "Only JPEG and PNG images are supported" }
            require(bounds.outWidth <= 16384 && bounds.outHeight <= 16384) { "Image dimensions exceed 16384 pixels" }
            // Android 14 gain-map JPEGs are HDR even though their MIME type is JPEG.
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                val sample = BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply { inSampleSize = 8 })
                try { require(sample?.hasGainmap() != true) { "HDR gain-map images are not supported; import SDR" } } finally { sample?.recycle() }
            }
            return MediaAsset(id,file.path,name,AssetKind.IMAGE,width = bounds.outWidth,height = bounds.outHeight)
        }
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
            extractor.setDataSource(file.path)
            var video: MediaFormat? = null
            var audio = false
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    require(mime in setOf("video/avc","video/hevc","video/mp4v-es","video/x-vnd.on2.vp8","video/x-vnd.on2.vp9")) { "Unsupported video codec: $mime" }
                    val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
                    require(transfer != MediaFormat.COLOR_TRANSFER_ST2084 && transfer != MediaFormat.COLOR_TRANSFER_HLG && !format.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) { "HDR video is not supported; import an SDR version" }
                    video = format
                }
                if (mime.startsWith("audio/")) {
                    require(mime in setOf("audio/mp4a-latm","audio/mpeg","audio/raw","audio/flac","audio/opus","audio/vorbis","audio/3gpp","audio/amr-wb")) { "Unsupported audio codec: $mime" }
                    audio = true
                }
            }
            require(video != null || audio) { "No supported audio or video stream" }
            retriever.setDataSource(file.path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.times(1000)
                ?: (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.filter { it.containsKey(MediaFormat.KEY_DURATION) }.maxOfOrNull { it.getLong(MediaFormat.KEY_DURATION) } ?: 0
            require(duration > 0) { "Media has no readable duration" }
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation % 180 != 0) { val old = width; width = height; height = old }
            return MediaAsset(id,file.path,name,if (video != null) AssetKind.VIDEO else AssetKind.AUDIO,duration,width,height,audio)
        } finally { extractor.release(); retriever.release() }
    }

    fun exportArchive(project: Project, dest: File) {
        val folder = directory(project.id)
        val assets = project.assets.map { asset ->
            val source = File(asset.path).canonicalFile
            require(source.path.startsWith(folder.canonicalPath + File.separator)) { "Project media must be stored inside the project" }
            asset.copy(path = "media/${source.name}")
        }
        val temporary = File(dest.parentFile,"${dest.name}.partial")
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("project.json")); zip.write(ProjectFiles.encode(project.copy(assets = assets)).toByteArray()); zip.closeEntry()
                project.assets.distinctBy { it.path }.forEach { asset ->
                    val file = File(asset.path)
                    // Missing media references survive and can be reported by the editor.
                    if (file.isFile) { zip.putNextEntry(ZipEntry("media/${file.name}")); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
                }
            }
            java.nio.file.Files.move(temporary.toPath(),dest.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    fun importArchive(uri: Uri): Project {
        val id = newId()
        val staging = File(root,"import-$id").also { it.mkdirs() }
        val final = directory(id)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Archive cannot be opened")
            val entries = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(entries.add(entry.name) && entries.size <= 10000) { "Duplicate entries or too many files" }
                    require(entry.name.split('/').none { it == ".." || it == "." } && !entry.name.contains('\\')) { "Invalid archive path" }
                    val output = ProjectFiles.safeChild(staging,entry.name)
                    require(entry.name == "project.json" || entry.name.startsWith("media/")) { "Unexpected archive content" }
                    if (entry.isDirectory) output.mkdirs() else {
                        output.parentFile?.mkdirs()
                        output.outputStream().use { sink ->
                            val buffer = ByteArray(65536)
                            var entryBytes = 0L
                            while (true) { val n = zip.read(buffer); if (n < 0) break; total += n; entryBytes += n
                                require(total <= 16L*1024*1024*1024) { "Archive exceeds 16 GB limit" }
                                require(entry.name != "project.json" || entryBytes <= ProjectFiles.MAX_JSON_BYTES) { "Project metadata exceeds 16 MB limit" }
                                sink.write(buffer,0,n) }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val imported = ProjectFiles.read(File(staging,"project.json"))
            val rewritten = imported.copy(id = id,modifiedAt = System.currentTimeMillis(),assets = imported.assets.map { asset ->
                require(asset.path.startsWith("media/")) { "Media must use portable archive paths" }
                val local = ProjectFiles.safeChild(staging,asset.path)
                if (local.isFile) inspect(local,asset.id,asset.name)
                asset.copy(path = ProjectFiles.safeChild(final,asset.path).path)
            })
            check(staging.renameTo(final)) { "Could not finish archive import" }
            save(rewritten)
            return rewritten
        } catch (error: Throwable) { staging.deleteRecursively(); final.deleteRecursively(); throw error }
    }

    fun createDemo(): Project {
        val project = Project(name = "First cut",width = 1080,height = 1920)
        val media = File(directory(project.id),"media").also { it.mkdirs() }
        val colors = listOf(Color.rgb(13,148,136) to Color.rgb(17,24,39), Color.rgb(124,58,237) to Color.rgb(236,72,153), Color.rgb(245,158,11) to Color.rgb(239,68,68))
        val assets = colors.mapIndexed { index,pair ->
            val bitmap = Bitmap.createBitmap(540,960,Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f,0f,540f,960f,pair.first,pair.second,Shader.TileMode.CLAMP) }
            canvas.drawRect(0f,0f,540f,960f,paint)
            paint.shader = null; paint.color = 0x44FFFFFF; canvas.drawCircle(390f,270f,200f,paint)
            paint.color = Color.WHITE; paint.textSize = 70f; paint.isFakeBoldText = true
            canvas.drawText(listOf("CREATE","YOUR","STORY")[index],35f,540f,paint)
            paint.textSize = 23f; paint.isFakeBoldText = false; canvas.drawText("Made with CutLab",38f,588f,paint)
            val file = File(media,"demo-${index+1}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            MediaAsset(path = file.path,name = "Scene ${index+1}",kind = AssetKind.IMAGE,durationUs = 3_000_000,width = 540,height = 960)
        }
        val result = project.copy(assets = assets,tracks = listOf(Track(name = "Main",clips = assets.mapIndexed { index,asset -> Clip(assetId = asset.id,startUs = index*3_000_000L,outUs = asset.durationUs,transition = Transition(if(index==0) TransitionKind.NONE else TransitionKind.DISSOLVE)) })))
        return EditorOps.reflow(result,result.tracks.first().id).also { save(it) }
    }
}
