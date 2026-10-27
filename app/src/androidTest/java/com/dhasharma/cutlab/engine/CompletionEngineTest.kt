package com.dhasharma.cutlab.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dhasharma.cutlab.model.*
import com.dhasharma.cutlab.data.ExportRecovery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class CompletionEngineTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(): MediaAsset {
        val file = File(context.cacheDir,"completion-source.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("reference.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        return MediaAsset(path = file.path,name = "Fixture",kind = AssetKind.VIDEO,durationUs = 3_000_000,width = 320,height = 180)
    }
    private fun diff(a: Bitmap,b: Bitmap): Long {
        var sum = 0L
        for(y in 0 until a.height step 3) for(x in 0 until a.width step 3) { val c = a.getPixel(x,y); val d = b.getPixel(x,y); sum += abs(Color.red(c)-Color.red(d))+abs(Color.green(c)-Color.green(d))+abs(Color.blue(c)-Color.blue(d)) }
        return sum
    }
    @Test fun effectsMasksTransitionsAndBackgroundRender() = runBlocking<Unit> {
        val asset = fixture(); val c = Clip(assetId = asset.id,outUs = 600_000)
        val p = Project(width = 320,height = 180,assets = listOf(asset),tracks = listOf(Track(clips = listOf(c))))
        TimelineRenderer(context).use { renderer ->
            val plain = renderer.renderFrame(p,200_000,320,180)
            for(kind in EffectKind.entries.filter { it != EffectKind.NONE }) {
                val changed = p.copy(tracks = listOf(Track(clips = listOf(c.copy(effect = VideoEffect(kind))))))
                val rendered = renderer.renderFrame(changed,200_000,320,180)
                assertTrue("$kind changes actual pixels",diff(plain,rendered)>1000); rendered.recycle()
            }
            for(kind in MaskKind.entries.filter { it != MaskKind.NONE && it != MaskKind.RECTANGLE }) {
                val masked = renderer.renderFrame(p.copy(tracks = listOf(Track(clips = listOf(c.copy(mask = Mask(kind,.1f)))))),200_000,320,180)
                assertTrue("$kind masks actual pixels",diff(plain,masked)>1000); masked.recycle()
            }
            val bg = renderer.renderFrame(p.copy(background = 0xFF173354,tracks = listOf(Track(clips = listOf(c.copy(transform = Transform(scale = .5f)))))),200_000,320,180)
            assertEquals(0xFF173354.toInt(),bg.getPixel(0,0)); bg.recycle()
            for(kind in TransitionKind.entries.filter { it != TransitionKind.NONE }) {
                val next = c.copy(id = newId(),startUs = 300_000,transition = Transition(kind,300_000),color = ColorAdjustments(saturation = 0f))
                val composed = renderer.renderFrame(p.copy(tracks = listOf(Track(clips = listOf(c,next)))),450_000,320,180)
                assertTrue("$kind produces a composed frame",diff(plain,composed)>1000); composed.recycle()
            }
            assertTrue(renderer.frameCacheHits>3); assertTrue(renderer.cachedFrameBytes<=16*1024*1024)
            plain.recycle()
        }
        val output = File(context.cacheDir,"completion-effects-export.mp4")
        val exportProject = p.copy(
            tracks = listOf(Track(clips = listOf(c.copy(effect = VideoEffect(EffectKind.SOFT_FOCUS),mask = Mask(MaskKind.DIAMOND,.1f))))),
            export = ExportSettings(height = 720,fps = 30)
        )
        VideoExporter(context).export(exportProject,output) {}
        assertTrue(output.length()>1000)
        TimelineRenderer(context).use { renderer -> android.media.MediaMetadataRetriever().use { reader ->
            val preview = renderer.renderFrame(exportProject,200_000,320,180)
            reader.setDataSource(output.path)
            val encoded = reader.getScaledFrameAtTime(200_000,android.media.MediaMetadataRetriever.OPTION_CLOSEST,320,180)!!
            assertTrue("Soft-focus sampling and mask match decoded export",diff(preview,encoded).toDouble()/(107*60*3)<18)
            preview.recycle(); encoded.recycle()
        } }
    }
    @Test fun interruptedExportRecoveryPreservesCompletedFiles() {
        val values = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME,"cutlab-recovery-test.mp4"); put(MediaStore.Video.Media.MIME_TYPE,"video/mp4"); put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/CutLab"); put(MediaStore.Video.Media.IS_PENDING,1) }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)!!
        val abandoned = File(context.cacheDir,"cutlab-export-working-recovery.mp4").apply { writeText("partial") }
        val keep = File(context.cacheDir,"completion-keep.mp4").apply { writeText("completed") }
        try {
            assertTrue(ExportRecovery.cleanInterrupted(context)>=2)
            assertFalse(abandoned.exists()); assertTrue(keep.exists())
            @Suppress("DEPRECATION")
            val all = MediaStore.setIncludePending(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            context.contentResolver.query(all,arrayOf(MediaStore.Video.Media._ID),"${MediaStore.Video.Media._ID}=?",arrayOf(uri.lastPathSegment),null)?.use { assertFalse(it.moveToFirst()) }
        } finally { runCatching { context.contentResolver.delete(uri,null,null) }; abandoned.delete(); keep.delete() }
    }
    @Test fun sustainedThirtySecondPreviewHasBoundedFrameCache() {
        val source = fixture()
        val clips = (0 until 10).map { Clip(assetId = source.id,startUs = it*3_000_000L,outUs = 3_000_000) }
        val p = Project(width = 320,height = 180,assets = listOf(source),tracks = listOf(Track(clips = clips)))
        val times = mutableListOf<Double>()
        TimelineRenderer(context).use { renderer ->
            for(i in 0 until 900) {
                val start = System.nanoTime(); val frame = renderer.renderFrame(p,i*1_000_000L/30,640,360); frame.recycle()
                times += (System.nanoTime()-start)/1e6
                assertTrue("Frame cache stays bounded",renderer.cachedFrameBytes<=16*1024*1024)
            }
            val sorted = times.sorted()
            val report = JSONObject().put("timeline_seconds",30).put("frames",900).put("width",640).put("height",360)
                .put("median_ms",sorted[450]).put("p95_ms",sorted[855]).put("total_ms",times.sum()).put("cache_bytes",renderer.cachedFrameBytes)
                .put("cache_hits",renderer.frameCacheHits).put("decoded_frames",renderer.previewDecodedFrames).put("fallback_frames",renderer.previewFallbackFrames).put("under_33ms_fraction",times.count { it<33.333 }/900.0)
                .put("scope","Software emulator decoding, composition and readback; excludes live audio and physical-device thermals")
            File(context.cacheDir,"completion-preview-benchmark.json").writeText(report.toString(2))
        }
    }
    @Test fun previewClockFollowsAudioOutputAndStops() = runBlocking<Unit> {
        val asset = fixture().copy(hasAudio = true)
        val p = Project(assets = listOf(asset),tracks = listOf(Track(clips = listOf(Clip(assetId = asset.id,outUs = 3_000_000)))))
        AudioPreview(context).use { preview ->
            preview.play(p,500_000)
            val initial = preview.positionUs()
            assertNotNull("Audible media provides a playback clock",initial)
            kotlinx.coroutines.delay(350)
            val advanced = preview.positionUs()!!
            assertTrue("Audio output advances the clock",advanced>initial!!+100_000)
            assertTrue("Clock remains within the source timeline",advanced in 600_000..1_500_000)
            preview.stop()
            repeat(30) { if(preview.positionUs()!=null) kotlinx.coroutines.delay(50) }
            assertNull(preview.positionUs())
        }
    }

    @Test fun persistentDecoderMatchesPlatformSeekingAndColors() {
        val source = fixture()
        PreviewVideoDecoder(source.path).use { decoder ->
            android.media.MediaMetadataRetriever().use { reference ->
                reference.setDataSource(source.path)
                for(time in listOf(0L,33_333L,66_667L,200_000L,1_200_000L,500_000L,2_999_999L,0L)) {
                    val frame = decoder.frameAt(time,320,180)
                    val expected = reference.getScaledFrameAtTime(time,android.media.MediaMetadataRetriever.OPTION_CLOSEST,320,180)!!
                    assertEquals(expected.width,frame.width); assertEquals(expected.height,frame.height)
                    val error = diff(frame,expected).toDouble()/((frame.width+2)/3*((frame.height+2)/3)*3)
                    assertTrue("Persistent decode matches platform pixels at $time: $error",error<8)
                    frame.recycle(); expected.recycle()
                }
            }
        }
    }

    @Test fun persistentDecoderHonorsRotationMetadata() {
        val source = fixture()
        val output = File(context.cacheDir,"completion-rotated.mp4")
        val extractor = android.media.MediaExtractor()
        val muxer = android.media.MediaMuxer(output.path,android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(source.path)
            val input = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("video/") }
            extractor.selectTrack(input)
            val target = muxer.addTrack(extractor.getTrackFormat(input)); muxer.setOrientationHint(90); muxer.start()
            val buffer = java.nio.ByteBuffer.allocateDirect(1024*1024)
            val info = android.media.MediaCodec.BufferInfo()
            while(extractor.sampleTime>=0) {
                buffer.clear(); val size = extractor.readSampleData(buffer,0); if(size<0) break
                info.set(0,size,extractor.sampleTime,if(extractor.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(target,buffer,info); extractor.advance()
            }
            muxer.stop()
        } finally { extractor.release(); muxer.release() }
        PreviewVideoDecoder(output.path).use { decoder -> android.media.MediaMetadataRetriever().use { reader ->
            reader.setDataSource(output.path)
            val expected = reader.getScaledFrameAtTime(500_000,android.media.MediaMetadataRetriever.OPTION_CLOSEST,180,320)!!
            val frame = decoder.frameAt(500_000,180,320)
            assertEquals(expected.width,frame.width); assertEquals(expected.height,frame.height)
            assertTrue("Rotation matches platform pixels",diff(frame,expected).toDouble()/((frame.width+2)/3*((frame.height+2)/3)*3)<8)
            frame.recycle(); expected.recycle()
        } }
    }

}
