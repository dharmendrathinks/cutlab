package com.dhasharma.cutlab.model

import kotlin.math.*

/** Positions use fractions of the full project canvas, with the origin at its center. */
fun Transform.gesture(panX: Float, panY: Float, zoom: Float, turn: Float, anchorX: Float = x, anchorY: Float = y, aspect: Float = 1f): Transform {
    val nextScale = (scale * zoom).coerceIn(.05f,8f)
    val factor = nextScale / scale.coerceAtLeast(.001f)
    val angle = turn * PI.toFloat()/180
    val dx = (x-anchorX)*aspect; val dy = y-anchorY
    return copy(x = (anchorX+(dx*cos(angle)-dy*sin(angle))*factor/aspect+panX).coerceIn(-2f,2f),
        y = (anchorY+(dx*sin(angle)+dy*cos(angle))*factor+panY).coerceIn(-2f,2f),
        scale = nextScale,rotation = ((rotation+turn+180)%360+360)%360-180)
}

/** Existing animation keys survive direct manipulation; only the current key is inserted/replaced. */
fun Clip.withTransformAt(localUs: Long, value: Transform): Clip = if(keyframes.isEmpty()) copy(transform = value)
    else copy(keyframes = (keyframes.filterNot { it.timeUs == localUs }+Keyframe(localUs.coerceAtLeast(0),value)).sortedBy { it.timeUs })

fun Clip.visualTransformAt(localUs: Long): Transform {
    var result = transformAt(localUs)
    val style = text ?: return result
    val duration = minOf(style.animationDurationUs.coerceAtLeast(1),durationUs()/2).coerceAtLeast(1)
    fun apply(kind: TextAnimation, progress: Float) {
        val p = progress.coerceIn(0f,1f)
        result = when(kind) {
            TextAnimation.NONE -> result
            TextAnimation.FADE -> result.copy(opacity = result.opacity*p)
            TextAnimation.POP -> result.copy(opacity = result.opacity*p,scale = result.scale*(.65f+.35f*p))
            TextAnimation.SLIDE_UP -> result.copy(opacity = result.opacity*p,y = result.y+.08f*(1-p))
        }
    }
    apply(style.entrance,localUs.toFloat()/duration)
    apply(style.exit,(durationUs()-localUs).toFloat()/duration)
    return result
}
