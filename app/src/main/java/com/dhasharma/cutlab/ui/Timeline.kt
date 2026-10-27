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
import kotlin.math.*

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Timeline(project: Project,cursor: Long,selected: String?,modifier: Modifier = Modifier,snapping: Boolean = true,onSeek: (Long) -> Unit,onSelect: (String?) -> Unit,move: (String,Long) -> Unit,trim: (String,Long,Long) -> Unit,onAddMedia: () -> Unit,onAudio: () -> Unit,onMute: () -> Unit,onCover: () -> Unit = {}) {
    var zoom by remember { mutableFloatStateOf(50f) }
    val density = LocalDensity.current.density
    val pxSecond = min(zoom * density, 200_000f / max(1f, project.durationUs / 1e6f))
    val leftPadding = LocalConfiguration.current.screenWidthDp * density / 2
    val rowHeight = 52 * density
    val header = 64 * density
    val tracks = project.tracks.filter { it.clips.isNotEmpty() }
    val width = max(350f,project.durationUs / 1e6f * pxSecond / density + leftPadding * 2 / density)
    val horizontal = rememberScrollState()
    var edgeScroll by remember { mutableFloatStateOf(0f) }
    val vertical = rememberScrollState()
    val timelineView = LocalView.current
    var drawGeneration by remember { mutableIntStateOf(0) }
    DisposableEffect(timelineView) {
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if(focused) drawGeneration++ }
        timelineView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose { if(timelineView.viewTreeObserver.isAlive) timelineView.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }
    LaunchedEffect(cursor, pxSecond) {
        if(!horizontal.isScrollInProgress) horizontal.scrollTo((cursor / 1e6f * pxSecond).toInt().coerceAtLeast(0))
    }
    val latestSeek by rememberUpdatedState(onSeek)
    LaunchedEffect(horizontal, pxSecond) {
        snapshotFlow { horizontal.value to horizontal.isScrollInProgress }.collect { (position,scrolling) -> if(scrolling && edgeScroll == 0f) latestSeek(frameTime((position / pxSecond * 1e6).toLong(),project.export.fps).coerceAtMost(project.durationUs)) }
    }
    val cache = remember { TimelineMediaCache() }
    var thumbnails by remember { mutableStateOf<Map<String,List<Bitmap>>>(emptyMap()) }
    var waveforms by remember { mutableStateOf<Map<String,FloatArray>>(emptyMap()) }
    val sourceKeys = project.tracks.flatMap { it.clips }.map { it.id to cache.key(it) }
    LaunchedEffect(sourceKeys, project.assets) {
        val available = mutableMapOf<String,List<Bitmap>>()
        for(c in project.tracks.flatMap { it.clips }) {
            val asset = project.assets.firstOrNull { it.id == c.assetId } ?: continue
            if(asset.kind != AssetKind.AUDIO) try {
                available[c.id] = withContext(Dispatchers.IO) { cache.thumbnails(asset,c) }
                while(available.size > 1 && available.values.sumOf { frames -> frames.sumOf { it.allocationByteCount } } > 12 * 1024 * 1024) available.remove(available.keys.first())
                thumbnails = available.toMap()
            } catch(e: CancellationException) { throw e } catch(_: Exception) { /* Actual preview reports missing/corrupt media. */ }
        }
        thumbnails = available.toMap()
    }
    LaunchedEffect(project.assets) {
        val available = mutableMapOf<String,FloatArray>()
        for(asset in project.assets.filter { it.kind == AssetKind.AUDIO }) try {
            available[asset.id] = withContext(Dispatchers.IO) { cache.waveform(asset) }
            waveforms = available.toMap()
        } catch(e: CancellationException) { throw e } catch(_: Exception) { /* A waveform is optional; playback reports decoder failures. */ }
        waveforms = available.toMap()
    }
    DisposableEffect(Unit) { onDispose { cache.clear() } }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragTime by remember { mutableLongStateOf(0) }
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    var trimDelta by remember { mutableFloatStateOf(0f) }
    var trimEdge by remember { mutableStateOf<String?>(null) }
    val selectedClip = tracks.flatMap { it.clips }.find { it.id == selected }
    val primary = tracks.firstOrNull { it.kind == TrackKind.VIDEO }
    val primaryEnd = primary?.clips?.maxOfOrNull { it.startUs + it.durationUs } ?: 0L
    LaunchedEffect(edgeScroll) {
        while(edgeScroll != 0f) {
            val before = horizontal.value
            horizontal.scrollTo((before+edgeScroll).roundToInt().coerceIn(0,horizontal.maxValue))
            val actual = horizontal.value-before
            if(dragId != null) dragTime = (dragTime+actual/pxSecond*1e6).toLong().coerceAtLeast(0)
            if(trimEdge != null) trimDelta += actual
            delay(16)
        }
    }
    val zoomDescription = "Zoom ${zoom.roundToInt()} pixels per second"
    val timelineHeight = (64 + max(2,tracks.size) * 52 + 20).dp
    Box(modifier.background(Panel).semantics { contentDescription = "Timeline"; stateDescription = zoomDescription }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false,pass = PointerEventPass.Initial)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if(event.changes.count { it.pressed } >= 2) {
                        zoom = (zoom * event.calculateZoom()).coerceIn(20f,400f)
                        event.changes.forEach { it.consume() }
                    }
                } while(event.changes.any { it.pressed })
            }
        }) {
        Box(Modifier.fillMaxSize().verticalScroll(vertical)) {
            Box(Modifier.horizontalScroll(horizontal)) {
                // Recreate only the drawing node after dialog/window focus restoration;
                // scroll, selection, caches and timeline time remain outside this key.
                key(drawGeneration) {
                Canvas(Modifier.width(width.dp).height(timelineHeight)
                    .pointerInput(project,zoom) { detectTapGestures { point -> val time = ((point.x - leftPadding) / pxSecond * 1e6).toLong().coerceAtLeast(0); val index = ((point.y - header) / rowHeight).toInt(); val c = if(point.y >= header) tracks.getOrNull(index)?.clips?.lastOrNull { time in it.startUs until it.startUs + it.durationUs } else null; onSelect(c?.id); if(c == null && point.y < header) onSeek(frameTime(time,project.export.fps).coerceAtMost(project.durationUs)) } }
                    .pointerInput(project,zoom) { detectDragGesturesAfterLongPress(onDragStart = { point -> val time = ((point.x - leftPadding) / pxSecond * 1e6).toLong(); val index = ((point.y - header) / rowHeight).toInt(); val c = tracks.getOrNull(index)?.clips?.lastOrNull { time in it.startUs until it.startUs + it.durationUs }; dragId = c?.id; dragTime = c?.startUs ?: 0; onSelect(c?.id) },onDrag = { change,amount -> change.consume(); if(dragId != null) {
                        dragTime = (dragTime + amount.x / pxSecond * 1e6).toLong().coerceAtLeast(0)
                        val visible = change.position.x-horizontal.value
                        edgeScroll = when { visible < 32*density -> -6*density; visible > horizontal.viewportSize-32*density -> 6*density; else -> 0f }
                    } },onDragEnd = { dragId?.let { id -> val endpoints = tracks.flatMap { it.clips }.filter { it.id != id }.flatMap { listOf(it.startUs,it.startUs + it.durationUs) } + cursor; val c = tracks.flatMap { it.clips }.first { it.id == id }
                        val target = if(snapping) snapClipStart(dragTime,c.durationUs,endpoints,(8*density/pxSecond*1e6).toLong()) else frameTime(dragTime,project.export.fps)
                        move(id,target) }; dragId = null; edgeScroll = 0f },onDragCancel = { dragId = null; edgeScroll = 0f }) }

                ) {
                    val step = if(zoom < 35) 2 else 1
                    paint.textSize = 9 * density; paint.color = android.graphics.Color.rgb(140,140,140)
                    paint.textAlign = android.graphics.Paint.Align.CENTER
                    val firstSecond = floor((horizontal.value-leftPadding)/pxSecond).toInt().coerceAtLeast(0)
                    val lastSecond = ceil((horizontal.value+horizontal.viewportSize-leftPadding)/pxSecond).toInt().coerceAtLeast(0)
                    for(second in firstSecond..lastSecond) {
                        val x = leftPadding + second * pxSecond
                        if(second % step == 0) drawContext.canvas.nativeCanvas.drawText(clockLabel(second*1_000_000L),x,13*density,paint)
                        drawCircle(Color(0xFF8C8C8C),density,Offset(x+pxSecond/2,10*density))
                    }
                    paint.textAlign = android.graphics.Paint.Align.LEFT
                    tracks.forEachIndexed { index,track ->
                        val y = header + index * rowHeight + 3 * density
                        track.clips.forEach { clip -> val start = if(dragId == clip.id) dragTime else clip.startUs; val x = leftPadding + start / 1e6f * pxSecond; val w = max(4*density,clip.durationUs / 1e6f * pxSecond); val h = if(track.kind == TrackKind.VIDEO) 50*density else 34*density; val visibleStart = horizontal.value.toFloat(); val visibleEnd = visibleStart + horizontal.viewportSize; if(x+w < visibleStart || x > visibleEnd) return@forEach; val tint = when(track.kind) { TrackKind.AUDIO -> Color(0xFF315F64); TrackKind.TEXT -> Color(0xFFE5664B); else -> Color(0xFF344D71) }; drawRoundRect(tint,Offset(x,y),Size(w,h),CornerRadius(2*density))
                            val frames = thumbnails[clip.id].orEmpty()
                            val native = drawContext.canvas.nativeCanvas
                            native.save(); native.clipRect(x,y,x+w,y+h)
                            if(frames.isNotEmpty()) {
                                val tiles = max(1,ceil(w / (50*density)).toInt())
                                val firstTile = floor((visibleStart - x) / (50*density)).toInt().coerceIn(0,tiles-1)
                                val lastTile = ceil((visibleEnd - x) / (50*density)).toInt().coerceIn(0,tiles-1)
                                for(tile in firstTile..lastTile) {
                                    val frame = frames[min(frames.lastIndex,tile * frames.size / tiles)]
                                    val tileX = x + tile * 50 * density
                                    val side = min(frame.width,frame.height)
                                    val sourceRect = android.graphics.Rect((frame.width-side)/2,(frame.height-side)/2,(frame.width+side)/2,(frame.height+side)/2)
                                    native.drawBitmap(frame,sourceRect,android.graphics.RectF(tileX,y,tileX+50*density,y+h),null)
                                }

                            }
                            val waveform = waveforms[clip.assetId]
                            val source = project.assets.firstOrNull { it.id == clip.assetId }
                            if(waveform != null && source != null) {
                                val bars = max(1,(w / (3*density)).toInt())
                                val firstBar = floor((visibleStart - x) / w * bars).toInt().coerceIn(0,bars-1)
                                val lastBar = ceil((visibleEnd - x) / w * bars).toInt().coerceIn(0,bars-1)
                                for(bar in firstBar..lastBar) {
                                    val local = clip.durationUs * bar / bars
                                    val bin = (clip.sourceTimeUs(local).toDouble() / source.durationUs * waveform.size).toInt().coerceIn(waveform.indices)
                                    val amplitude = waveform[bin] * (h/2-3*density)
                                    val bx = x + bar * w / bars
                                    drawLine(Accent.copy(alpha = .8f),Offset(bx,y+h/2-amplitude),Offset(bx,y+h/2+amplitude),density)
                                }
                            }
                            native.restore()
                            if(clip.id == selected) {
                                drawRect(Color.White,Offset(x,y),Size(w,h),style = Stroke(2*density))
                                paint.color = android.graphics.Color.WHITE; paint.textSize = 9*density
                                drawContext.canvas.nativeCanvas.drawText("%.1fs".format(clip.durationUs/1e6f),x+6*density,y+13*density,paint)
                            }
                            if(track.kind != TrackKind.VIDEO) {
                                val label = clip.text?.text ?: project.assets.firstOrNull { it.id == clip.assetId }?.name ?: "Missing media"
                                paint.color = android.graphics.Color.WHITE; paint.textSize = 10*density
                                native.save(); native.clipRect(x+6*density,y,x+w-5*density,y+h); native.drawText(label,x+8*density,y+20*density,paint); native.restore()
                            }
                            clip.keyframes.forEach { key -> val kx = x + key.timeUs / 1e6f * pxSecond; drawCircle(Accent,2*density,Offset(kx,y+h-4*density)) }
                        }
                    }

                }
                }
                // Controls share the horizontal coordinate system with the clips.
                if(project.durationUs > 0) {
                    Row(Modifier.offset(x = (leftPadding/density-122).dp,y = 67.dp).height(50.dp),verticalAlignment = Alignment.CenterVertically) {
                        if(primary != null) Column(Modifier.width(40.dp).clickable(onClick = onMute),horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(if(primary.muted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,if(primary.muted) "Unmute clip" else "Mute clip",Modifier.size(20.dp),tint = Color(0xFFCCCCCC))
                            Text(if(primary.muted) "Unmute" else "Mute\nclip",fontSize = 8.sp,lineHeight = 10.sp,color = Color(0xFFCCCCCC),textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                        Spacer(Modifier.width(7.dp))
                        Box(Modifier.size(50.dp).clickable(onClick = onCover)) {
                            ProjectCover(project,Modifier.fillMaxSize())
                            Text("Cover",fontSize = 8.sp,color = Color.White,modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = .5f)).padding(horizontal = 4.dp))
                        }
                    }
                }
                if(tracks.isEmpty() || (tracks.size == 1 && primary != null)) {
                    Box(Modifier.offset(x = (leftPadding/density).dp,y = (if(primary == null) 67 else 120).dp).width(max(145f,primaryEnd/1e6f*pxSecond/density).dp).height(34.dp).background(Color(0xFF2C2C2C)).clickable(onClick = onAudio).padding(start = 8.dp),contentAlignment = Alignment.CenterStart) { Text("＋  Add audio",color = Color(0xFFBBBBBB),fontSize = 12.sp) }
                }

            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(end = 12.dp,top = 77.dp).size(30.dp).background(Color.White,RoundedCornerShape(5.dp)).clickable(onClick = onAddMedia),contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Add,"Add media",tint = Color(0xFF3B3B3B),modifier = Modifier.size(24.dp)) }
        // Handles are above the fixed add button and outside the scrolling gesture owner.
        // Its expanded touch target must never steal a drag on a visible trim handle.
        Box(Modifier.fillMaxSize().clipToBounds()) {
                if(selectedClip != null) {
                    val index = tracks.indexOfFirst { t -> t.clips.any { it.id == selectedClip.id } }
                    listOf("start","end").forEach { edge ->
                        val boundary = if(edge == "start") selectedClip.startUs else selectedClip.startUs + selectedClip.durationUs
                        val x = leftPadding/density + boundary/1e6f*pxSecond/density - horizontal.value/density + if(edge == "start") -18 else 0
                        Box(Modifier.offset(x = (x + if(trimEdge == edge) trimDelta/density else 0f).dp,y = (67+index*52-vertical.value/density).dp).width(18.dp).height(if(tracks[index].kind == TrackKind.VIDEO) 50.dp else 34.dp).background(Color.White,RoundedCornerShape(2.dp))
                            .semantics { contentDescription = if(edge == "start") "Trim start" else "Trim end" }
                            .pointerInput(selectedClip,edge,pxSecond) {
                                detectDragGestures(onDragStart = { trimEdge = edge; trimDelta = 0f },onDragCancel = { trimEdge = null; trimDelta = 0f; edgeScroll = 0f },onDragEnd = {
                                    var deltaUs = frameTime(kotlin.math.abs(trimDelta/pxSecond*1_000_000).toLong(),project.export.fps)*(if(trimDelta<0) -1 else 1)
                                    if(snapping) {
                                        val endpoints = project.tracks.flatMap { it.clips }.filterNot { it.id == selectedClip.id }.flatMap { listOf(it.startUs,it.startUs+it.durationUs) }+cursor
                                        val timelineBoundary = boundary+deltaUs
                                        endpoints.minByOrNull { abs(it-timelineBoundary) }?.let { target -> if(abs(target-timelineBoundary)<8*density/pxSecond*1e6) deltaUs = target-boundary }
                                    }
                                    val c = selectedClip
                                    val source = project.assets.find { it.id == c.assetId }
                                    val maximum = if(source == null || source.kind == AssetKind.IMAGE || c.freezeUs != null) max(c.outUs,30_000_000L) else source.durationUs
                                    // Map the dragged timeline boundary through speed curves/reverse.
                                    val local = if(edge == "start") deltaUs else c.durationUs + deltaUs
                                    val boundaryUs = if(c.freezeUs != null) {
                                        val fraction = local.toDouble()/c.durationUs.coerceAtLeast(1)
                                        c.inUs + (fraction*(c.outUs-c.inUs)).toLong()
                                    } else if(local in 0..c.durationUs) c.sourceBoundaryAt(local) else {
                                        val sourcePerTimeline = (c.outUs-c.inUs).toDouble()/c.durationUs.coerceAtLeast(1)
                                        val origin = if(edge == "start") { if(c.reverse) c.outUs else c.inUs } else { if(c.reverse) c.inUs else c.outUs }
                                        origin + (deltaUs*sourcePerTimeline*(if(c.reverse) -1 else 1)).toLong()
                                    }
                                    val minimum = minOf(50_000L,c.outUs-c.inUs)
                                    val editsIn = (edge == "start") != c.reverse
                                    val start = if(editsIn) boundaryUs.coerceIn(0,c.outUs-minimum) else c.inUs
                                    val end = if(!editsIn) boundaryUs.coerceIn(c.inUs+minimum,maximum) else c.outUs
                                    trim(c.id,start,end); trimEdge = null; trimDelta = 0f; edgeScroll = 0f
                                }) { change,amount -> change.consume(); trimDelta += amount.x
                                    val visible = leftPadding+boundary/1e6f*pxSecond-horizontal.value+(if(edge == "start") -18*density else 0f)+trimDelta+change.position.x
                                    edgeScroll = when { visible<32*density -> -6*density; visible>horizontal.viewportSize-32*density -> 6*density; else -> 0f } }
                            },contentAlignment = Alignment.Center) { Box(Modifier.width(2.dp).height(12.dp).background(Color(0xFFB9B9B9),CircleShape)) }
                    }
                }
        }
        Canvas(Modifier.fillMaxWidth().height(timelineHeight)) {
            drawLine(Color.White,Offset(size.width/2,30*density),Offset(size.width/2,size.height-5*density),strokeWidth = 1.5f*density,cap = StrokeCap.Round)
        }
    }
}
