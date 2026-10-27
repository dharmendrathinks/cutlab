package com.dhasharma.cutlab.model

import kotlin.math.*

private fun Float.safeSpeed(): Double = if (isFinite()) toDouble().coerceIn(.05, 16.0) else 1.0
private val lastCurve = ThreadLocal<Pair<List<SpeedPoint>, List<SpeedPoint>>>()
internal fun Clip.curve(): List<SpeedPoint> {
    lastCurve.get()?.let { if (it.first === speedCurve) return it.second }
    val points = speedCurve.filter { it.position.isFinite() && it.speed.isFinite() }
        .map { SpeedPoint(it.position.coerceIn(0f, 1f), it.speed.safeSpeed().toFloat()) }
        .associateBy { it.position }.values.sortedBy { it.position }.toMutableList()
    if (points.isEmpty()) return listOf(SpeedPoint(0f, 1f), SpeedPoint(1f, 1f)).also { lastCurve.set(speedCurve to it) }
    if (points.first().position > 0) points.add(0, points.first().copy(position = 0f))
    if (points.last().position < 1) points.add(points.last().copy(position = 1f))
    return points.also { lastCurve.set(speedCurve to it) }
}

// Integral of 1 / speed over normalized source distance. Linear speed ramps
// require a logarithm; averaging the endpoint speeds causes drift after splits.
private fun integral(a: Double, b: Double, width: Double): Double =
    if (abs(b - a) < 1e-9) width / a else width * ln(b / a) / (b - a)

internal fun Clip.elapsedAtSourceFraction(fraction: Double): Double {
    val end = fraction.coerceIn(0.0, 1.0)
    val points = curve()
    var result = if (fraction < 0) fraction / points.first().speed else if (fraction > 1) (fraction-1) / points.last().speed else 0.0
    for (index in 0 until points.lastIndex) {
        val a = points[index]; val b = points[index+1]
        val width = (b.position - a.position).toDouble()
        val used = (end - a.position).coerceIn(0.0, width)
        if (used > 0) {
            val endpoint = a.speed + (b.speed - a.speed) * used / width
            result += integral(a.speed.toDouble(), endpoint, used)
        }
    }
    return result * (outUs - inUs).coerceAtLeast(0) / speed.safeSpeed()
}

fun Clip.durationUs(): Long {
    val duration = if (freezeUs != null || speedCurve.isEmpty()) (outUs - inUs).coerceAtLeast(0) / speed.safeSpeed()
    else elapsedAtSourceFraction(1.0)
    return duration.roundToLong().coerceAtLeast(0)
}

fun Clip.sourceBoundaryAt(localTimeUs: Long): Long {
    val length = (outUs - inUs).coerceAtLeast(0)
    if (length == 0L) return inUs
    if (freezeUs != null) return freezeUs.coerceIn(inUs, outUs)
    if (speedCurve.isEmpty()) {
        val distance = (localTimeUs.coerceAtLeast(0).toDouble()*speed.safeSpeed()).coerceIn(0.0,length.toDouble())
        return (if (reverse) outUs-distance else inUs+distance).roundToLong().coerceIn(inUs,outUs)
    }
    val total = elapsedAtSourceFraction(1.0)
    val time = localTimeUs.toDouble().coerceIn(0.0, total)
    var remaining = (if (reverse) total - time else time) * speed.safeSpeed() / length
    var fraction = 1.0
    val points = curve()
    for (index in 0 until points.lastIndex) {
        val a = points[index]; val b = points[index+1]
        val width = (b.position - a.position).toDouble()
        val full = integral(a.speed.toDouble(), b.speed.toDouble(), width)
        if (remaining <= full + 1e-12) {
            val slope = (b.speed - a.speed) / width
            val distance = if (abs(slope) < 1e-9) remaining * a.speed
                else a.speed * expm1(remaining * slope) / slope
            fraction = a.position + distance.coerceIn(0.0, width)
            break
        }
        remaining -= full
    }
    return (inUs + fraction * length).roundToLong().coerceIn(inUs, outUs)
}

fun Clip.sourceTimeUs(localTimeUs: Long): Long = freezeUs?.coerceAtLeast(0) ?: sourceBoundaryAt(localTimeUs)
    .coerceIn(inUs, max(inUs, outUs - 1))

fun Project.durationUs(): Long = tracks.flatMap { it.clips }.maxOfOrNull { it.startUs + it.durationUs() } ?: 0L

private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun interpolate(a: Transform, b: Transform, t: Float) = Transform(
    mix(a.x,b.x,t), mix(a.y,b.y,t), mix(a.scale,b.scale,t), mix(a.rotation,b.rotation,t),
    mix(a.opacity,b.opacity,t), if (t < 1) a.flipX else b.flipX, if (t < 1) a.flipY else b.flipY,
    mix(a.cropLeft,b.cropLeft,t), mix(a.cropTop,b.cropTop,t), mix(a.cropRight,b.cropRight,t), mix(a.cropBottom,b.cropBottom,t)
)

fun Clip.transformAt(localTimeUs: Long): Transform {
    val keys = (listOf(Keyframe(0, transform)) + keyframes).associateBy { it.timeUs }.values.sortedBy { it.timeUs }
    if (localTimeUs <= keys.first().timeUs) return keys.first().transform
    for ((a,b) in keys.zipWithNext()) if (localTimeUs <= b.timeUs)
        return interpolate(a.transform,b.transform, (localTimeUs-a.timeUs).toFloat()/(b.timeUs-a.timeUs))
    return keys.last().transform
}

internal fun Clip.sliceCurve(newInUs: Long, newOutUs: Long): List<SpeedPoint> {
    if (speedCurve.isEmpty() || outUs <= inUs) return emptyList()
    val a = (newInUs-inUs).toFloat()/(outUs-inUs)
    val b = (newOutUs-inUs).toFloat()/(outUs-inUs)
    if (b <= a) return emptyList()
    val points = curve()
    fun at(p: Float): Float {
        for ((left,right) in points.zipWithNext()) if (p <= right.position) {
            val f = ((p-left.position)/(right.position-left.position)).coerceIn(0f,1f)
            return mix(left.speed,right.speed,f)
        }
        return points.last().speed
    }
    return (listOf(SpeedPoint(a,at(a))) + points.filter { it.position > a && it.position < b } + SpeedPoint(b,at(b)))
        .map { it.copy(position = (it.position-a)/(b-a)) }
}
