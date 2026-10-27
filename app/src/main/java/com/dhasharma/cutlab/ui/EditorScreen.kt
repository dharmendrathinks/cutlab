package com.dhasharma.cutlab.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import android.view.ViewTreeObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dhasharma.cutlab.model.*
import com.dhasharma.cutlab.data.ProjectStore
import com.dhasharma.cutlab.engine.TimelineRenderer
import com.dhasharma.cutlab.engine.VideoExporter
import com.dhasharma.cutlab.engine.AudioPreview
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

@Composable fun EditorScreen(initial: Project, store: ProjectStore, onBack: (Project) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history = remember { EditorHistory(initial) }
    var revision by remember { mutableIntStateOf(0) }
    val project = remember(revision) { history.project }
    val saveMutex = remember { Mutex() }
    var selected by remember { mutableStateOf<String?>(null) }
    val clip = project.tracks.flatMap { it.clips }.firstOrNull { it.id == selected }
    var cursor by remember { mutableLongStateOf(0) }
    var previewDraft by remember { mutableStateOf<Pair<String,Transform>?>(null) }
    val renderProject = previewDraft?.let { (id,value) -> EditorOps.updateClip(project,id) { it.withTransformAt((cursor-it.startUs).coerceAtLeast(0),value) } } ?: project
    var seekVersion by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Saved on device") }
    var tab by remember { mutableStateOf("") }
    var positionDialog by remember { mutableStateOf(false) }
    var fullPreview by remember { mutableStateOf(false) }
    var snapping by remember { mutableStateOf(true) }
    var projectMenu by remember { mutableStateOf(false) }
    var overlayImport by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<Uri?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableStateOf<Float?>(null) }
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var recordingStart by remember { mutableLongStateOf(0) }
    var rename by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(project.name) }
    val renderer = remember { TimelineRenderer(context) }
    val previewDisposed = remember { AtomicBoolean(false) }
    val cleanupScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val audio = remember { AudioPreview(context) { failure -> playing = false; error = "Audio preview: ${failure.message ?: "Playback failed"}" } }
    fun edit(label: String, mutation: (Project) -> Project) {
        playing = false
        try { history.apply(label) { before ->
            val after = mutation(before)
            val primary = before.tracks.firstOrNull { it.kind == TrackKind.VIDEO }
            val reflow = label in setOf("Delete", "Duplicate", "Trim in", "Trim out", "Speed", "Speed curve", "Constant", "Ramp up", "Ramp down", "Montage", "Transition", "Transition duration")
            if(reflow && primary != null && primary.clips.any { it.id == selected }) EditorOps.reflow(after,primary.id) else after
        }; revision++; status = "Saving…" } catch(e: Exception) { error = e.message ?: "Edit could not be applied" }
    }
    fun seek(value: Long) { playing = false; cursor = value.coerceIn(0,project.durationUs); seekVersion++ }
    fun addAsset(asset: MediaAsset, overlay: Boolean, at: Long? = null) {
        val id = newId()
        edit("Import media") { p ->
            val kind = if(asset.kind == AssetKind.AUDIO) TrackKind.AUDIO else TrackKind.VIDEO
            val primary = p.tracks.firstOrNull { it.kind == kind }
            val start = at ?: if(overlay || kind == TrackKind.AUDIO) cursor else primary?.clips?.maxOfOrNull { it.startUs + it.durationUs } ?: 0L
            val newClip = Clip(id = id, assetId = asset.id, startUs = start, outUs = asset.durationUs.coerceAtLeast(100_000))
            val tracks = if(overlay || primary == null || kind == TrackKind.AUDIO) p.tracks + Track(name = if(kind == TrackKind.AUDIO) "Audio" else "Overlay", kind = kind, clips = listOf(newClip)) else p.tracks.map { if(it.id == primary.id) it.copy(clips = it.clips + newClip) else it }
            p.copy(assets = p.assets + asset, tracks = tracks, width = if(p.assets.isEmpty() && asset.width > 0) canvasSize(asset).first else p.width, height = if(p.assets.isEmpty() && asset.height > 0) canvasSize(asset).second else p.height)
        }
        selected = null; tab = ""
    }
    val importMedia = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if(uris.isNotEmpty()) scope.launch { importing = true; playing = false; try { for(uri in uris) { val asset = withContext(Dispatchers.IO) { store.importMedia(project.id, uri) }; addAsset(asset,overlayImport) } } catch(e: Exception) { error = "Import failed: ${e.message}" }; importing = false }
    }
    val archive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri != null) scope.launch { try { withContext(Dispatchers.IO) { val file = File(context.cacheDir,"${project.id}.cutlab.zip"); store.exportArchive(project,file); context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } } ?: kotlin.error("Could not open destination"); file.delete() }; status = "Project archive exported" } catch(e: Exception) { error = "Archive export failed: ${e.message}" } }
    }
    fun toggleRecording() {
        if(recording) {
            try {
                recorder?.stop(); recorder?.release(); recorder = null; recording = false
                val file = recordingFile ?: return
                scope.launch { try { val asset = withContext(Dispatchers.IO) { store.importMedia(project.id, Uri.fromFile(file)) }; addAsset(asset, true, recordingStart); file.delete() } catch(e: Exception) { error = "Recording import failed: ${e.message}" } }
            } catch(e: Exception) { recording = false; recorder?.release(); recorder = null; error = "Recording too short or microphone unavailable" }
        } else {
            try {
                playing = false
                val file = File(context.cacheDir,"voice-${System.currentTimeMillis()}.m4a")
                val r = if(Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
                r.setAudioSource(MediaRecorder.AudioSource.MIC); r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); r.setAudioSamplingRate(48000); r.setAudioEncodingBitRate(128000); r.setOutputFile(file.absolutePath); r.prepare(); r.start()
                recorder = r; recordingFile = file; recordingStart = cursor; recording = true
            } catch(e: Exception) { error = "Could not start microphone: ${e.message}" }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if(granted) toggleRecording() else error = "Microphone permission is needed to record a voice track." }
    fun record() { if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) toggleRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    fun textClip() { val id = newId(); edit("Add text") { p -> p.copy(tracks = p.tracks + Track(name = "Text", kind = TrackKind.TEXT, clips = listOf(Clip(id = id,startUs = cursor,outUs = if(p.durationUs > cursor) minOf(3_000_000L,p.durationUs-cursor) else 3_000_000L,text = TextStyle(fontSize = 160f,bold = false))))) }; selected = id; tab = "Text style" }
    fun launchImport(overlay: Boolean) { overlayImport = overlay; importMedia.launch(if(tab == "Audio" || tab == "Volume") arrayOf("audio/*") else arrayOf("video/*","image/*","audio/*")) }
    fun startExport() {
        if(exportJob != null) return
        playing = false; exportDialog = false; exportResult = null
        val exporting = history.project
        exportJob = scope.launch { exportProgress = 0f; var pending: Uri? = null; var exportFile: File? = null
            try {
                val dest = File(context.cacheDir,"cutlab-export-working-${System.currentTimeMillis()}.mp4").also { exportFile = it }
                VideoExporter(context).export(exporting,dest) { value -> exportProgress = value }
                val savedUri = withContext(Dispatchers.IO) {
                    val values = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME,"${project.name.replace(Regex("[^A-Za-z0-9 _-]"),"").take(50).ifBlank { "CutLab" }}-${System.currentTimeMillis()}.mp4"); put(MediaStore.Video.Media.MIME_TYPE,"video/mp4"); put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/CutLab"); put(MediaStore.Video.Media.IS_PENDING,1) }
                    val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: kotlin.error("Could not create export in Movies")
                    pending = uri
                    context.contentResolver.openOutputStream(uri)?.use { output -> dest.inputStream().use { it.copyTo(output) } } ?: kotlin.error("Cannot write output")
                    currentCoroutineContext().ensureActive()
                    context.contentResolver.update(uri,ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING,0) },null,null); pending = null; dest.delete(); uri
                }
                exportResult = savedUri
            } catch(e: CancellationException) { status = "Export cancelled"; throw e }
            catch(e: Exception) { error = "Export failed: ${e.message}" }
            finally {
                withContext(NonCancellable) {
                    try { withContext(Dispatchers.IO) { exportFile?.delete(); pending?.let { context.contentResolver.delete(it,null,null) } } }
                    finally { exportProgress = null; exportJob = null }
                }
            }
        }
    }
    suspend fun persistLatest() { withContext(Dispatchers.IO) { saveMutex.withLock { store.save(history.project) } }; status = "Saved on device" }
    LaunchedEffect(project) { delay(600); try { persistLatest() } catch(e: CancellationException) { throw e } catch(e: Exception) { status = "Save failed"; error = "Project could not be saved: ${e.message}" } }
    LaunchedEffect(renderProject,playing,seekVersion) {
        try {
            if(playing) {
                if(cursor >= project.durationUs) cursor = 0
                audio.play(project,cursor)
                val began = SystemClock.elapsedRealtimeNanos(); val start = cursor
                while(isActive && playing) {
                    val frameBegan = SystemClock.elapsedRealtimeNanos()
                    val time = audio.positionUs() ?: (start + (SystemClock.elapsedRealtimeNanos() - began) / 1000)
                    if(time >= project.durationUs) { cursor = project.durationUs; playing = false; break }
                    cursor = time
                    val frame = withContext(Dispatchers.Default) { synchronized(renderer) { if(previewDisposed.get()) null else renderer.renderFrame(renderProject,time,max(2,640*project.width/max(project.width,project.height)),max(2,640*project.height/max(project.width,project.height))) } }
                    bitmap = frame
                    val spentMs = (SystemClock.elapsedRealtimeNanos()-frameBegan)/1_000_000
                    delay((1000L/project.export.fps-spentMs).coerceAtLeast(1))
                }
            } else {
                audio.stop()
                bitmap = withContext(Dispatchers.Default) { synchronized(renderer) { if(previewDisposed.get()) null else renderer.renderFrame(renderProject,cursor.coerceAtMost(project.durationUs),max(2,640*project.width/max(project.width,project.height)),max(2,640*project.height/max(project.width,project.height))) } }
            }
        } catch(e: CancellationException) { throw e } catch(e: Exception) { playing = false; status = "Preview unavailable"; error = "Preview: ${e.message}" }
        finally { if(!playing) audio.stop() }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) { val observer = LifecycleEventObserver { _,event -> if(event == Lifecycle.Event.ON_STOP) { playing = false; audio.stop(); if(recording) toggleRecording()
        scope.launch { try { persistLatest() } catch(e: CancellationException) { throw e } catch(e: Exception) { status = "Save failed"; error = "Project could not be saved: ${e.message}" } }
    } }; lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer); recorder?.runCatching { stop(); release() }; previewDisposed.set(true); cleanupScope.launch { try { audio.close(); renderer.close() } finally { cleanupScope.cancel() } } } }
    fun backTool() { if(tab.isNotEmpty()) tab = "" else selected = null }
    fun selectClip(id: String?) { selected = id; tab = "" }
    fun activeClip(): Clip? = clip ?: project.tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.clips?.firstOrNull { cursor in it.startUs..(it.startUs + it.durationUs) }
    fun openTool(label: String) { if(label !in listOf("Audio", "Text", "Overlay", "Ratio")) selected = activeClip()?.id; tab = label }
    BackHandler { if(exportJob != null) confirmCancel = true else if(exportResult != null) exportResult = null else if(exportDialog) exportDialog = false else if(fullPreview) fullPreview = false else if(tab.isNotEmpty() || selected != null) backTool() else if(recording) toggleRecording() else { playing = false; onBack(history.project) } }
    val navigationInset = WindowInsets.navigationBars.getBottom(LocalDensity.current).toFloat()
    BoxWithConstraints(Modifier.fillMaxSize().background(Backdrop).drawBehind { drawRect(Panel,Offset(0f,size.height-navigationInset),Size(size.width,navigationInset)) }.safeDrawingPadding()) {
    val hasPanel = tab !in listOf("", "Audio", "Text", "Overlay", "Edit")
    val toolPanelHeight = if(tab == "Text style") 240.dp else 160.dp
    val normalPreviewHeight = (maxHeight - 158.dp) * .583f
    val previewHeight = minOf(normalPreviewHeight,(maxHeight - (if(tab == "Text style") 292.dp + toolPanelHeight else if(hasPanel) 178.dp + toolPanelHeight else 218.dp)).coerceAtLeast(60.dp))
    Column(Modifier.fillMaxSize().then(if(exportDialog) Modifier.clearAndSetSemantics {} else Modifier)) {
        Spacer(Modifier.height(46.dp))
        Box(Modifier.fillMaxWidth().then(if(fullPreview) Modifier.weight(1f) else Modifier.height(previewHeight)).background(Backdrop),contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(),"Video preview",Modifier.fillMaxSize()) }
            if(project.durationUs == 0L) Column(Modifier.clickable { tab = ""; launchImport(false) }.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.AddPhotoAlternate,null,tint = Muted,modifier = Modifier.size(36.dp)); Text("Add media to begin",color = Muted,fontSize = 12.sp,modifier = Modifier.padding(top = 8.dp)) }
            if(project.durationUs>0) PreviewEditor(renderProject,cursor,selected,!playing && !importing && !exportDialog,snapping,Modifier.fillMaxSize(),
                onSelect = { selected = it },onDraft = { id,value -> playing = false; previewDraft = id to value },
                onCommit = { id,value -> previewDraft = null; edit("Preview transform") { p -> EditorOps.updateClip(p,id) { it.withTransformAt((cursor-it.startUs).coerceAtLeast(0),value) } } },
                onCancel = { previewDraft = null })
            if(importing) CircularProgressIndicator(Modifier.size(30.dp))
        }
        Box(Modifier.fillMaxWidth().height(40.dp).background(Color(0xFF181818))) {
            Row(Modifier.align(Alignment.CenterStart).semantics { contentDescription = "Playhead position" }.clickable { positionDialog = true }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(clockLabel(cursor),fontSize = 12.sp,color = Color.White)
                Text(" / ${clockLabel(project.durationUs)}",fontSize = 12.sp,color = Muted)
            }
            IconButton(onClick = { playing = !playing },enabled = project.durationUs > 0 && !recording,modifier = Modifier.align(Alignment.Center).size(40.dp)) { Icon(if(playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,if(playing) "Pause" else "Play",Modifier.size(27.dp)) }
            Row(Modifier.align(Alignment.CenterEnd),verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { snapping = !snapping },modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Layers,if(snapping) "Snapping on" else "Snapping off",Modifier.size(23.dp),tint = if(snapping) Color.White else Muted) }
                IconButton(onClick = { playing = false; history.undo(); revision++ },enabled = history.canUndo,modifier = Modifier.size(34.dp)) { Icon(Icons.Outlined.Undo,"Undo",Modifier.size(23.dp)) }
                IconButton(onClick = { playing = false; history.redo(); revision++ },enabled = history.canRedo,modifier = Modifier.size(34.dp)) { Icon(Icons.Outlined.Redo,"Redo",Modifier.size(23.dp)) }
                IconButton(onClick = { fullPreview = !fullPreview },modifier = Modifier.size(34.dp)) { Icon(if(fullPreview) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,"Expand preview",Modifier.size(23.dp)) }
            }
        }
        if(!fullPreview) {
            Timeline(project,cursor,selected,Modifier.fillMaxWidth().weight(1f),snapping,
                onSeek = ::seek,onSelect = ::selectClip,
                move = { id,time -> edit("Move clip") { EditorOps.drop(it,id,time) } },
                trim = { id,start,end -> edit("Trim out") { EditorOps.trim(it,id,start,end) }; cursor = cursor.coerceAtMost(history.project.durationUs) },
                onAddMedia = { tab = ""; launchImport(false) },onAudio = { selected = null; tab = "Audio" },
                onCover = { tab = "Cover"; selected = null; seek(project.coverUs) },
                onMute = { edit("Mute track") { p -> val primary = p.tracks.firstOrNull { it.kind == TrackKind.VIDEO }; p.copy(tracks = p.tracks.map { if(it.id == primary?.id) it.copy(muted = !it.muted) else it }) } })
            if(hasPanel) Row(Modifier.fillMaxWidth().height(32.dp).background(Color(0xFF262626)).padding(horizontal = 12.dp),verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::backTool,modifier = Modifier.size(30.dp)) { Icon(Icons.Outlined.ChevronLeft,"Back to tools") }
                Text(if(tab == "Text style") "Text" else tab,fontSize = 12.sp,modifier = Modifier.weight(1f))
                IconButton(onClick = ::backTool,modifier = Modifier.size(30.dp)) { Icon(Icons.Outlined.Check,"Done") }
            }
            else EditorToolBar(tab,clip,recording, onBack = ::backTool, onTool = ::openTool,
                onText = ::textClip,onImport = ::launchImport,onRecord = ::record,
                onSplit = { clip?.let { c -> edit("Split") { EditorOps.split(it,c.id,cursor) } } },
                onDelete = { clip?.let { c -> edit("Delete") { EditorOps.remove(it,c.id) } }; selected = null; tab = "" },
                onDuplicate = { clip?.let { c -> edit("Duplicate") { EditorOps.duplicate(it,c.id) } } })
            if(tab == "Cover") Column(Modifier.fillMaxWidth().background(Panel).padding(16.dp)) {
                Text("Choose a frame for the project cover",fontSize = 13.sp)
                Slider(cursor.toFloat(),{ seek(it.toLong()) },valueRange = 0f..project.durationUs.coerceAtLeast(1).toFloat())
                Button(onClick = { edit("Project cover") { it.copy(coverUs = cursor.coerceAtMost((project.durationUs-1).coerceAtLeast(0))) }; tab = "" }) { Text("Set cover") }
            }
            else if(tab == "Ratio") RatioPanel(project,onRatio = { w,h -> edit("Canvas ratio") { it.copy(width = w,height = h) } },onBackground = { color -> edit("Canvas background") { it.copy(background = color) } })
            else if(tab !in listOf("", "Audio", "Text", "Overlay", "Edit")) {
                Box(Modifier.fillMaxWidth().height(toolPanelHeight).background(Color(0xFF262626))) {
                    ClipTools(project,clip,when(tab) { "Text style" -> "Text"; "Volume" -> "Audio"; "Trim" -> "Edit"; else -> tab },cursor,::edit,{ selected = it },::launchImport,::textClip,::record,recording)
                }
            }
        }
    }
    if(exportDialog) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .65f)).clickable { exportDialog = false })
        Column(Modifier.fillMaxWidth().background(Color(0xFF191919))) {
            Spacer(Modifier.height(46.dp))
            ExportOptions(project) { settings -> edit("Export settings") { it.copy(export = settings) } }
        }
    }
    EditorHeader(project.export.height,exportDialog,project.durationUs > 0 && !recording,
        onBack = { playing = false; onBack(history.project) },onSettings = { exportDialog = !exportDialog },
        onExport = ::startExport,onMore = { projectMenu = true })
    }
    if(positionDialog) PositionDialog(project,cursor,selected,::seek) { positionDialog = false }
    if(projectMenu) AlertDialog(onDismissRequest = { projectMenu = false },title = { Text(project.name) },text = { Column {
        Text(status,color = Muted)
        TextButton(onClick = { projectMenu = false; name = project.name; rename = true }) { Text("Rename project") }
        TextButton(onClick = { projectMenu = false; archive.launch("${project.name}.cutlab.zip") }) { Text("Export project archive") }
    } },confirmButton = { TextButton(onClick = { projectMenu = false }) { Text("Done") } })
    if(exportProgress != null || exportResult != null) ExportScreen(exportProgress,exportResult,bitmap,
        onCancel = { confirmCancel = true },onDone = { exportResult = null },onError = { error = it })
    if(confirmCancel) AlertDialog(onDismissRequest = { confirmCancel = false },title = { Text("Cancel export?") },text = { Text("Your project is saved. The unfinished video will be removed.") },
        confirmButton = { TextButton(onClick = { confirmCancel = false; exportJob?.cancel() }) { Text("Stop export") } },
        dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Continue exporting") } })

    if(rename) AlertDialog(onDismissRequest = { rename = false },title = { Text("Rename project") },text = { OutlinedTextField(name,{ name = it },singleLine = true) },confirmButton = { TextButton(onClick = { edit("Rename") { it.copy(name = name.ifBlank { "Untitled" }) }; rename = false }) { Text("Save") } },dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } })
    error?.let { ErrorDialog(it) { error = null } }
}
