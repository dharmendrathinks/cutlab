package com.dhasharma.cutlab.model

import kotlin.math.*

fun frameTime(timeUs: Long,fps: Int): Long = (round(timeUs.coerceAtLeast(0)*fps.coerceAtLeast(1)/1e6)*1e6/fps.coerceAtLeast(1)).roundToLong()
fun snapClipStart(startUs: Long,durationUs: Long,targets: List<Long>,thresholdUs: Long): Long {
    val candidates = targets.flatMap { listOf(it,it-durationUs) }.filter { it>=0 }
    val nearest = candidates.minByOrNull { abs(it-startUs) }
    return if(nearest != null && abs(nearest-startUs)<=thresholdUs) nearest else startUs.coerceAtLeast(0)
}
val localFilters: Map<String,ColorAdjustments> = linkedMapOf(
    "Original" to ColorAdjustments(), "Warm" to ColorAdjustments(temperature = .35f,saturation = 1.12f),
    "Cool" to ColorAdjustments(temperature = -.35f,contrast = 1.08f), "Cinema" to ColorAdjustments(contrast = 1.2f,saturation = .8f,temperature = -.12f),
    "Vivid" to ColorAdjustments(contrast = 1.12f,saturation = 1.45f), "Soft" to ColorAdjustments(exposure = .15f,contrast = .85f,saturation = .9f),
    "Noir" to ColorAdjustments(contrast = 1.35f,saturation = 0f), "Faded" to ColorAdjustments(contrast = .75f,saturation = .65f,temperature = .2f)
)
