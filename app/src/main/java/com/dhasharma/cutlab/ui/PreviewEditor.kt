package com.dhasharma.cutlab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import com.dhasharma.cutlab.engine.TextRaster
import com.dhasharma.cutlab.model.*
import kotlin.math.*

internal data class PreviewLayer(val clip: Clip,val center: Offset,val width: Float,val height: Float,val angle: Float) {
    fun corner(x: Float,y: Float): Offset {
        val r = angle*PI.toFloat()/180
        return center+Offset(x*cos(r)-y*sin(r),x*sin(r)+y*cos(r))
    }
    fun contains(point: Offset,padding: Float = 0f): Boolean {
        val p = point-center; val r = -angle*PI.toFloat()/180
        return abs(p.x*cos(r)-p.y*sin(r))<=width/2+padding && abs(p.x*sin(r)+p.y*cos(r))<=height/2+padding
    }
    val handle get() = corner(width/2,height/2)
}

internal fun previewLayers(project: Project,time: Long,size: Size): List<PreviewLayer> {
    val fit = min(size.width/project.width,size.height/project.height)
    val canvasW = project.width*fit; val canvasH = project.height*fit
    return project.tracks.filter { it.kind != TrackKind.AUDIO }.flatMap { it.clips.sortedBy { c -> c.startUs } }
        .filter { time>=it.startUs && time<it.startUs+it.durationUs }.mapNotNull { clip ->
            val asset = project.assets.find { it.id == clip.assetId }
            val t = clip.visualTransformAt(time-clip.startUs)
            val dimensions = clip.text?.let { TextRaster.measure(it,project.width).let { m -> m.width.toFloat() to m.height.toFloat() } }
                ?: asset?.let { a -> val scale = min(project.width.toFloat()/a.width.coerceAtLeast(1),project.height.toFloat()/a.height.coerceAtLeast(1)); a.width*scale to a.height*scale } ?: return@mapNotNull null
            PreviewLayer(clip,Offset(size.width/2+t.x*canvasW,size.height/2+t.y*canvasH),dimensions.first*fit*t.scale*(1-t.cropLeft-t.cropRight),dimensions.second*fit*t.scale*(1-t.cropTop-t.cropBottom),t.rotation)
        }
}

/** Raw gesture accumulation avoids sticky snapping; committing once preserves useful undo history. */
@Composable fun PreviewEditor(project: Project,cursor: Long,selected: String?,enabled: Boolean,snapping: Boolean,modifier: Modifier,
    onSelect: (String?) -> Unit,onDraft: (String,Transform) -> Unit,onCommit: (String,Transform) -> Unit,onCancel: () -> Unit) {
    val currentProject by rememberUpdatedState(project)
    val currentCursor by rememberUpdatedState(cursor)
    val currentSelected by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    val draft by rememberUpdatedState(onDraft)
    val commit by rememberUpdatedState(onCommit)
    val cancel by rememberUpdatedState(onCancel)
    val snap by rememberUpdatedState(snapping)
    var guides by remember { mutableStateOf(false to false) }
    Canvas(modifier.semantics { contentDescription = "Preview transform controls" }
        .pointerInput(enabled) {
            if(!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val p = currentProject
                val canvasSize = Size(size.width.toFloat(),size.height.toFloat())
                val layers = previewLayers(p,currentCursor,canvasSize)
                val active = layers.find { it.clip.id == currentSelected }
                val handle = active?.let { (down.position-it.handle).getDistance()<24*density } == true
                val hit = if(handle) active else layers.lastOrNull { it.contains(down.position,6*density) }
                if(hit == null) { select(null); return@awaitEachGesture }
                val id = hit.clip.id
                select(id)
                var value = hit.clip.transformAt(currentCursor-hit.clip.startUs)
                var shown = value
                val fit = min(canvasSize.width/p.width,canvasSize.height/p.height)
                val cw = p.width*fit; val ch = p.height*fit
                var moved = false; var ended = false; var distance = 0f
                try {
                    do {
                        val event = awaitPointerEvent()
                        if(event.changes.any { it.isConsumed }) break
                        if(event.changes.none { it.pressed }) { ended = true; break }
                        val pan = event.calculatePan()
                        val zoom = event.calculateZoom()
                        val rotation = event.calculateRotation()
                        distance += pan.getDistance()+abs(zoom-1)*100+abs(rotation)
                        if(!moved && distance<viewConfiguration.touchSlop) continue
                        moved = true
                        if(handle && event.changes.count { it.pressed } == 1) {
                            val change = event.changes.first { it.pressed }
                            val before = change.previousPosition-hit.center; val after = change.position-hit.center
                            val z = after.getDistance()/before.getDistance().coerceAtLeast(1f)
                            val r = (atan2(after.y,after.x)-atan2(before.y,before.x))*180/PI.toFloat()
                            value = value.gesture(0f,0f,z,r)
                        } else {
                            val centroid = event.calculateCentroid(useCurrent = false)
                            value = value.gesture(pan.x/cw,pan.y/ch,zoom,rotation,(centroid.x-canvasSize.width/2)/cw,(centroid.y-canvasSize.height/2)/ch,p.width.toFloat()/p.height)
                        }
                        val sx = snap && abs(value.x*cw)<6*density
                        val sy = snap && abs(value.y*ch)<6*density
                        val angle = round(value.rotation/90)*90
                        shown = value.copy(x = if(sx) 0f else value.x,y = if(sy) 0f else value.y,rotation = if(snap && abs(value.rotation-angle)<3) angle else value.rotation)
                        guides = sx to sy
                        draft(id,shown)
                        event.changes.forEach { it.consume() }
                    } while(true)
                    if(moved && ended) commit(id,shown) else cancel()
                } finally { guides = false to false; if(!ended) cancel() }
            }
        }) {
        if(enabled) {
            val fit = min(size.width/project.width,size.height/project.height)
            val cw = project.width*fit; val ch = project.height*fit
            if(guides.first) drawLine(Accent,Offset(size.width/2,(size.height-ch)/2),Offset(size.width/2,(size.height+ch)/2),density)
            if(guides.second) drawLine(Accent,Offset((size.width-cw)/2,size.height/2),Offset((size.width+cw)/2,size.height/2),density)
            previewLayers(project,cursor,size).find { it.clip.id == selected }?.let { layer ->
                val points = listOf(layer.corner(-layer.width/2,-layer.height/2),layer.corner(layer.width/2,-layer.height/2),layer.handle,layer.corner(-layer.width/2,layer.height/2))
                val path = Path().apply { moveTo(points[0].x,points[0].y); points.drop(1).forEach { lineTo(it.x,it.y) }; close() }
                drawPath(path,Color.White,style = Stroke(1.2f*density))
                drawCircle(Color.White,12*density,layer.handle)
                val c = layer.handle; val d = 5*density
                drawLine(Color.Black,c-Offset(d,d),c+Offset(d,d),1.5f*density)
                drawLine(Color.Black,c+Offset(d,d),c+Offset(d,-d/3),1.5f*density)
                drawLine(Color.Black,c+Offset(d,d),c+Offset(-d/3,d),1.5f*density)
            }
        }
    }
}
