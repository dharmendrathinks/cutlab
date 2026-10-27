package com.dhasharma.cutlab.ui

import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.view.ViewTreeObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.dhasharma.cutlab.model.*
import com.dhasharma.cutlab.data.ProjectStore
import kotlinx.coroutines.*

val Accent = Color(0xFF00C1D4)
val Panel = Color(0xFF1C1C1C)
val Muted = Color(0xFF9D9D9D)
val Backdrop = Color(0xFF141414)

private tailrec fun Context.activity(): Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable fun CutLabApp() {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color(0xFF072D28), background = Backdrop, surface = Panel, secondary = Accent)) {
        val context = LocalContext.current
        val store = remember { ProjectStore(context) }
        val scope = rememberCoroutineScope()
        var current by remember { mutableStateOf<Project?>(null) }
        var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
        var message by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(false) }
        var pendingDelete by remember { mutableStateOf<Project?>(null) }
        var rename by remember { mutableStateOf(false) }
        var name by remember { mutableStateOf("") }
        suspend fun refresh() { projects = withContext(Dispatchers.IO) { store.list() } }
        fun runWork(work: suspend () -> Unit) { if(loading) return; loading = true; scope.launch { try { work() } catch(e: CancellationException) { throw e } catch(e: Exception) { message = e.message ?: "Unable to open project" } finally { loading = false } } }
        LaunchedEffect(Unit) { runWork { withContext(Dispatchers.IO) { com.dhasharma.cutlab.data.ExportRecovery.cleanInterrupted(context) }; refresh() } }
        val archiveImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri != null) runWork { current = withContext(Dispatchers.IO) { store.importArchive(uri) } } }
        val newProjectMedia = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if(uris.isNotEmpty()) runWork {
                current = withContext(Dispatchers.IO) {
                    var p = Project(name = SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(Date()))
                    try {
                        for(uri in uris) {
                            val asset = store.importMedia(p.id,uri)
                            val track = p.tracks.first()
                            val clip = Clip(assetId = asset.id,startUs = track.clips.sumOf { it.durationUs },outUs = asset.durationUs.coerceAtLeast(100_000))
                            p = p.copy(assets = p.assets + asset,tracks = listOf(track.copy(clips = track.clips + clip)),
                                width = if(p.assets.isEmpty() && asset.width > 0) canvasSize(asset).first else p.width,
                                height = if(p.assets.isEmpty() && asset.height > 0) canvasSize(asset).second else p.height)
                        }
                        store.save(p); p
                    } catch(e: Exception) { store.delete(p.id); throw e }
                }
            }
        }
        val view = LocalView.current
        val lightBars = current == null
        DisposableEffect(view, lightBars) {
            val window = view.context.activity()?.window
            fun applyBarAppearance() { window?.let { WindowCompat.getInsetsController(it,view).apply { isAppearanceLightStatusBars = lightBars; isAppearanceLightNavigationBars = lightBars } } }
            // Dialogs/SAF can restore their saved flags after composition. Reapply on actual
            // window focus and after the view traversal, using the Activity-hosted Compose view.
            val update = Runnable { applyBarAppearance() }
            val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if(focused) view.post(update) }
            view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            view.post(update)
            onDispose { view.removeCallbacks(update); if(view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
        }
        Surface(Modifier.fillMaxSize()) {
            if(current != null) {
                key(current!!.id) { EditorScreen(current!!, store, onBack = { project -> runWork { projects = withContext(Dispatchers.IO) { store.save(project); store.list() }; current = null } }) }
            } else {
                MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF087F86), onPrimary = Color.White, surface = Color.White, background = Color.White)) {
                Column(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding().padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Movie,null,tint = Color(0xFF171B23),modifier = Modifier.size(26.dp))
                        Text("CutLab",fontSize = 21.sp,fontWeight = FontWeight.Bold,color = Color(0xFF111318),modifier = Modifier.padding(start = 8.dp))
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { archiveImport.launch(arrayOf("application/zip","application/octet-stream")) }) { Icon(Icons.Outlined.FolderOpen,"Import project",tint = Color(0xFF171B23)) }
                    }
                    Row(Modifier.fillMaxWidth().height(90.dp).padding(top = 3.dp,bottom = 17.dp).background(Color(0xFFF0F3F4),RoundedCornerShape(14.dp)),verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable(enabled = !loading) { runWork { current = withContext(Dispatchers.IO) { store.createDemo() } } },horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.PlayCircleOutline,null,tint = Color(0xFF141820)); Text("Try demo",fontSize = 11.sp,color = Color(0xFF141820),modifier = Modifier.padding(top = 6.dp)) }
                        Column(Modifier.weight(1f).clickable(enabled = !loading) { name = "Untitled film"; rename = true },horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.NoteAdd,null,tint = Color(0xFF141820)); Text("Blank project",fontSize = 11.sp,color = Color(0xFF141820),modifier = Modifier.padding(top = 6.dp)) }
                    }
                    Spacer(Modifier.height(7.dp))
                    Card(onClick = { if(!loading) newProjectMedia.launch(arrayOf("video/*","image/*")) },colors = CardDefaults.cardColors(containerColor = Color.Transparent,contentColor = Color(0xFF061B25)),shape = RoundedCornerShape(12.dp),modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().height(120.dp).background(Brush.linearGradient(listOf(Color(0xFFB5EEF5),Color(0xFF3DD2E2),Color(0xFF59B5F5)))),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.Center) {
                            Icon(Icons.Outlined.AddBox,null,modifier = Modifier.size(30.dp))
                            Text("New project",fontSize = 22.sp,fontWeight = FontWeight.Bold,modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 31.dp,bottom = 14.dp),verticalAlignment = Alignment.CenterVertically) { Text("Projects",fontWeight = FontWeight.Bold,fontSize = 19.sp,color = Color(0xFF10131A)); Spacer(Modifier.weight(1f)); Text("${projects.size}",color = Muted,fontSize = 12.sp) }
                    if(loading) LinearProgressIndicator(Modifier.fillMaxWidth(),color = Accent)
                    if(projects.isEmpty() && !loading) Text("Your projects will appear here",color = Muted,fontSize = 13.sp,modifier = Modifier.padding(top = 28.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(projects,key = { it.id }) { p ->
                            Row(Modifier.fillMaxWidth().clickable { runWork { current = withContext(Dispatchers.IO) { store.load(p.id) } } },verticalAlignment = Alignment.CenterVertically) {
                                ProjectCover(p)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(p.name,fontWeight = FontWeight.SemiBold,color = Color(0xFF141820),fontSize = 14.sp,maxLines = 1)
                                    Text(SimpleDateFormat("MM/dd/yyyy HH:mm",Locale.getDefault()).format(Date(p.modifiedAt)),color = Muted,fontSize = 12.sp,modifier = Modifier.padding(top = 5.dp))
                                    Text(clockLabel(p.durationUs),color = Muted,fontSize = 12.sp,modifier = Modifier.padding(top = 4.dp))
                                }
                                IconButton(onClick = { pendingDelete = p }) { Icon(Icons.Outlined.MoreVert,"Delete ${p.name}",tint = Muted,modifier = Modifier.size(21.dp)) }
                            }
                        }
                    }
                }

            }
            }
        }
        if(rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text("New project") }, text = { OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true) }, confirmButton = { TextButton(onClick = { rename = false; runWork { val p = Project(name = name.ifBlank { "Untitled film" }); withContext(Dispatchers.IO) { store.save(p) }; current = p } }) { Text("Create") } }, dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } })
        pendingDelete?.let { p -> AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Delete project?") }, text = { Text("Delete “${p.name}” and its imported media from CutLab? Exported videos and original files remain available.") }, confirmButton = { TextButton(onClick = { pendingDelete = null; runWork { withContext(Dispatchers.IO) { store.delete(p.id) }; refresh() } }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }) }
        message?.let { ErrorDialog(it) { message = null } }
    }
}

@Composable fun ErrorDialog(message: String, close: () -> Unit) { AlertDialog(onDismissRequest = close, title = { Text("CutLab") }, text = { Text(message) }, confirmButton = { TextButton(onClick = close) { Text("OK") } }) }
fun timeLabel(us: Long): String { val ms = us.coerceAtLeast(0) / 1000; return "%02d:%02d.%01d".format(ms / 60000, ms / 1000 % 60, ms / 100 % 10) }

val Project.durationUs: Long get() = durationUs()
val Clip.durationUs: Long get() = durationUs()

@Composable fun ProjectCover(project: Project,modifier: Modifier = Modifier.size(72.dp)) {
    val context = LocalContext.current
    var image by remember(project.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(project.id,project.modifiedAt) {
        image = withContext(Dispatchers.IO) {
            runCatching { com.dhasharma.cutlab.engine.TimelineRenderer(context).use { renderer ->
                renderer.renderFrame(project,project.coverUs.coerceIn(0,(project.durationUs-1).coerceAtLeast(0)),maxOf(2,192*project.width/maxOf(project.width,project.height)),maxOf(2,192*project.height/maxOf(project.width,project.height)))
            } }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFF0F3F4)),contentAlignment = Alignment.Center) {
        val cover = image
        if(cover != null) Image(cover.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale = ContentScale.Crop)
        else Icon(Icons.Outlined.VideoLibrary,null,tint = Color(0xFF939CA3))
    }
}

/** Keep editing coordinates stable across source resolutions while inheriting its aspect ratio. */
fun canvasSize(asset: MediaAsset): Pair<Int,Int> {
    val scale = 1080.0 / minOf(asset.width,asset.height).coerceAtLeast(1)
    return ((asset.width*scale/2).toInt().coerceAtLeast(1)*2) to ((asset.height*scale/2).toInt().coerceAtLeast(1)*2)
}
