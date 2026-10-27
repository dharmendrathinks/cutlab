package com.dhasharma.cutlab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dhasharma.cutlab.model.*
import kotlin.math.roundToLong

@Composable fun PositionDialog(project: Project,cursor: Long,selected: String?,seek: (Long)->Unit,close: ()->Unit) {
    val frame = 1_000_000.0/project.export.fps
    val clip = project.tracks.flatMap { it.clips }.find { it.id == selected }
    val boundaries = project.tracks.flatMap { it.clips }.flatMap { listOf(it.startUs,it.startUs+it.durationUs) }.distinct().sorted()
    val keys = clip?.keyframes?.map { it.timeUs+clip.startUs }.orEmpty()
    fun move(time: Long) = seek(time.coerceIn(0,(project.durationUs-1).coerceAtLeast(0)))
    AlertDialog(onDismissRequest = close,title = { Text("Playhead position") },text = { Column {
        Text("${timeLabel(cursor)} · frame ${(cursor/frame).roundToLong()} / ${(project.durationUs/frame).roundToLong()}")
        Slider(cursor.toFloat(),{ move(frameTime(it.toLong(),project.export.fps)) },valueRange = 0f..project.durationUs.coerceAtLeast(1).toFloat())
        Row { TextButton(onClick = { move(((cursor/frame).roundToLong()-1).times(frame).roundToLong()) }) { Text("Previous frame") }; TextButton(onClick = { move(((cursor/frame).roundToLong()+1).times(frame).roundToLong()) }) { Text("Next frame") } }
        Row { TextButton(onClick = { move(boundaries.lastOrNull { it<cursor } ?: 0) }) { Text("Previous cut") }; TextButton(onClick = { move(boundaries.firstOrNull { it>cursor } ?: project.durationUs) }) { Text("Next cut") } }
        if(keys.isNotEmpty()) Row { TextButton(onClick = { move(keys.lastOrNull { it<cursor } ?: keys.first()) }) { Text("Previous key") }; TextButton(onClick = { move(keys.firstOrNull { it>cursor } ?: keys.last()) }) { Text("Next key") } }
    } },confirmButton = { TextButton(onClick = close) { Text("Done") } })
}
