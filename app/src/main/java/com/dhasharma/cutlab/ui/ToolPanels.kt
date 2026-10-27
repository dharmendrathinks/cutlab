package com.dhasharma.cutlab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dhasharma.cutlab.model.*
import kotlin.math.roundToLong

@Composable fun Adjustment(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String = "", onCommit: (Float) -> Unit) {
    var draft by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { draft = value }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row { Text(label, color = Muted, fontSize = 12.sp); Spacer(Modifier.weight(1f)); Text("%.2f%s".format(draft, unit), fontSize = 12.sp) }
        Slider(value = draft.coerceIn(range), onValueChange = { draft = it }, onValueChangeFinished = { onCommit(draft) }, valueRange = range, modifier = Modifier.height(30.dp))
    }
}

@Composable fun Choices(values: List<String>, selected: String? = null, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { values.forEach { label -> FilterChip(selected = label == selected, onClick = { onSelect(label) }, label = { Text(label, fontSize = 12.sp) }) } }
}

@Composable fun ClipTools(project: Project, clip: Clip?, tab: String, cursor: Long, edit: (String, (Project) -> Project) -> Unit, select: (String?) -> Unit, onAddMedia: (Boolean) -> Unit, onText: () -> Unit, onRecord: () -> Unit, recording: Boolean) {
    if(clip == null) {
        Column(Modifier.padding(20.dp)) { Text("Build your timeline", fontSize = 18.sp); Text("Import media, add a text layer, or select a clip to edit.", color = Muted, modifier = Modifier.padding(vertical = 10.dp)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { onAddMedia(false) }) { Text("Add media") }; OutlinedButton(onClick = onText) { Text("Text") } } }
        return
    }
    fun update(label: String, change: (Clip) -> Clip) = edit(label) { EditorOps.updateClip(it, clip.id, change) }
    val textStyle = clip.text
    val asset = project.assets.firstOrNull { it.id == clip.assetId }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        when(tab) {
            "Edit" -> {
                val ordered = project.tracks.first { track -> track.clips.any { it.id == clip.id } }.clips.sortedBy { it.startUs }
                val index = ordered.indexOfFirst { it.id == clip.id }
                val actions = buildList {
                    if(cursor > clip.startUs && cursor < clip.startUs + clip.durationUs) add("Split")
                    add("Duplicate"); add("Delete")
                    if(index > 0) add("Earlier")
                    if(index < ordered.lastIndex) add("Later")
                    add("Start here")
                    if(asset?.kind == AssetKind.VIDEO) add("Insert freeze")
                }
                Choices(actions) { action ->
                    when(action) {
                        "Split" -> edit("Split") { EditorOps.split(it, clip.id, cursor) }
                        "Duplicate" -> edit("Duplicate") { EditorOps.duplicate(it, clip.id) }
                        "Delete" -> { edit("Delete") { EditorOps.remove(it, clip.id) }; select(null) }
                        "Start here" -> edit("Move") { EditorOps.move(it, clip.id, cursor) }
                        "Earlier", "Later" -> { val track = project.tracks.first { t -> t.clips.any { it.id == clip.id } }; val index = track.clips.sortedBy { it.startUs }.indexOfFirst { it.id == clip.id }; edit("Reorder") { EditorOps.reorder(it, track.id, clip.id, (index + if(action == "Earlier") -1 else 1).coerceIn(0, track.clips.lastIndex)) } }
                        "Insert freeze" -> edit("Insert freeze") { EditorOps.insertFreeze(it,clip.id,cursor) }
                    }
                }
                Text("Trim source • ${timeLabel(clip.inUs)} – ${timeLabel(clip.outUs)}", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Muted, fontSize = 12.sp)
                val maximum = (if(asset == null || asset.kind == AssetKind.IMAGE || clip.freezeUs != null) maxOf(clip.outUs, 30_000_000L) else asset.durationUs).coerceAtLeast(100_000)
                Adjustment("In", clip.inUs / 1e6f, 0f..((clip.outUs - 50_000).coerceAtLeast(1) / 1e6f), "s") { value -> edit("Trim in") { EditorOps.trim(it, clip.id, (value * 1e6).roundToLong(), clip.outUs) } }
                Adjustment("Out", clip.outUs / 1e6f, ((clip.inUs + 50_000) / 1e6f)..(maximum / 1e6f), "s") { value -> edit("Trim out") { EditorOps.trim(it, clip.id, clip.inUs, (value * 1e6).roundToLong()) } }
            }
            "Transform" -> {
                val at = (cursor-clip.startUs).coerceIn(0,clip.durationUs)
                val value = clip.transformAt(at)
                fun transform(label: String,change: (Transform)->Transform) = update(label) { it.withTransformAt(at,change(it.transformAt(at))) }

                Choices(listOf("Flip H", "Flip V", "Rotate 90°", "Reset")) { action -> transform(action) { t -> when(action) { "Flip H" -> t.copy(flipX = !t.flipX); "Flip V" -> t.copy(flipY = !t.flipY); "Rotate 90°" -> t.copy(rotation = (t.rotation+90)%360); else -> Transform() } } }
                Adjustment("Horizontal position",value.x,-1f..1f) { v -> transform("Position") { it.copy(x = v) } }
                Adjustment("Vertical position",value.y,-1f..1f) { v -> transform("Position") { it.copy(y = v) } }
                Adjustment("Scale",value.scale,.05f..8f) { v -> transform("Scale") { it.copy(scale = v) } }
                Adjustment("Rotation",value.rotation,-180f..180f,"°") { v -> transform("Rotation") { it.copy(rotation = v) } }
                Adjustment("Opacity",value.opacity,0f..1f) { v -> transform("Opacity") { it.copy(opacity = v) } }
                listOf("Left","Top","Right","Bottom").forEach { edge -> val amount = when(edge) { "Left" -> value.cropLeft; "Top" -> value.cropTop; "Right" -> value.cropRight; else -> value.cropBottom }
                    Adjustment("Crop $edge",amount,0f..0.45f) { v -> transform("Crop") { when(edge) { "Left" -> it.copy(cropLeft = v); "Top" -> it.copy(cropTop = v); "Right" -> it.copy(cropRight = v); else -> it.copy(cropBottom = v) } } }
                }
            }

            "Speed" -> {
                Adjustment("Playback speed", clip.speed, .25f..4f, "×") { v -> update("Speed") { it.copy(speed = v, speedCurve = emptyList()) } }
                Choices(listOf("Constant", "Ramp up", "Ramp down", "Montage", "Reverse"), if(clip.reverse) "Reverse" else if(clip.speedCurve.isEmpty()) "Constant" else null) { action -> update(action) { c -> when(action) { "Reverse" -> c.copy(reverse = !c.reverse); "Constant" -> c.copy(speedCurve = emptyList()); "Ramp up" -> c.copy(speedCurve = listOf(SpeedPoint(0f,.5f),SpeedPoint(1f,2f))); "Ramp down" -> c.copy(speedCurve = listOf(SpeedPoint(0f,2f),SpeedPoint(1f,.5f))); else -> c.copy(speedCurve = listOf(SpeedPoint(0f,1f),SpeedPoint(.3f,3f),SpeedPoint(.5f,.4f),SpeedPoint(.7f,3f),SpeedPoint(1f,1f))) } } }
                clip.speedCurve.forEachIndexed { index, point -> Adjustment("Curve at ${(point.position * 100).toInt()}%", point.speed, .25f..4f, "×") { v -> update("Speed curve") { c -> c.copy(speedCurve = c.speedCurve.mapIndexed { i, p -> if(i == index) p.copy(speed = v) else p }) } } }
                Text("Reverse and speed changes apply to both video and source audio.", Modifier.padding(16.dp), color = Muted, fontSize = 12.sp)
            }
            "Text" -> {
                if(textStyle == null) { Text("Select a text clip or add a new text layer.", Modifier.padding(16.dp), color = Muted); TextButton(onClick = onText) { Text("Add text") } }
                else TextStylePanel(clip) { label,change -> update(label) { it.copy(text = change(it.text!!)) } }
            }

            "Audio" -> {
                Choices(listOfNotNull(if(clip.muted) "Unmute" else "Mute", if(asset?.hasAudio == true && asset.kind == AssetKind.VIDEO) "Extract audio" else null, if(recording) "Stop recording" else "Record voice", "Import audio")) { action -> when(action) {
                    "Mute", "Unmute" -> update(action) { it.copy(muted = !it.muted) }
                    "Import audio" -> onAddMedia(false)
                    "Record voice", "Stop recording" -> onRecord()
                    "Extract audio" -> if(asset != null && asset.hasAudio) edit("Extract audio") { p -> val copy = asset.copy(id = newId(), kind = AssetKind.AUDIO, name = "Audio · ${asset.name}"); p.copy(assets = p.assets + copy, tracks = p.tracks + Track(name = "Extracted audio", kind = TrackKind.AUDIO, clips = listOf(clip.copy(id = newId(), assetId = copy.id, transform = Transform(), keyframes = emptyList())))).let { EditorOps.updateClip(it, clip.id) { c -> c.copy(muted = true) } } }
                } }
                Adjustment("Volume", clip.volume, 0f..2f) { v -> update("Volume") { it.copy(volume = v) } }
                val maxFade = clip.durationUs / 2e6f
                Adjustment("Fade in", clip.fadeInUs / 1e6f, 0f..maxOf(.01f,maxFade), "s") { v -> update("Fade in") { it.copy(fadeInUs = (v * 1e6).roundToLong()) } }
                Adjustment("Fade out", clip.fadeOutUs / 1e6f, 0f..maxOf(.01f,maxFade), "s") { v -> update("Fade out") { it.copy(fadeOutUs = (v * 1e6).roundToLong()) } }
            }
            "Filters" -> {
                Choices(localFilters.keys.toList(),localFilters.entries.find { it.value == clip.color }?.key) { name -> update("Filter") { it.copy(color = localFilters.getValue(name)) } }
                Text("Local color presets. Fine-tune them in Adjust.",Modifier.padding(16.dp),color = Muted,fontSize = 12.sp)
            }
            "Effects" -> {
                val names = linkedMapOf("None" to EffectKind.NONE,"Mono" to EffectKind.MONO,"Sepia" to EffectKind.SEPIA,"Vignette" to EffectKind.VIGNETTE,"Mosaic" to EffectKind.MOSAIC,"Soft focus" to EffectKind.SOFT_FOCUS)
                Choices(names.keys.toList(),names.entries.find { it.value == clip.effect.kind }?.key) { name -> update("Effect") { it.copy(effect = it.effect.copy(kind = names.getValue(name))) } }
                Adjustment("Effect intensity",clip.effect.intensity,0f..1f) { v -> update("Effect intensity") { it.copy(effect = it.effect.copy(intensity = v)) } }
            }
            "Color" -> {
                val c = clip.color
                Adjustment("Exposure", c.exposure, -2f..2f) { v -> update("Exposure") { it.copy(color = it.color.copy(exposure = v)) } }
                Adjustment("Contrast", c.contrast, 0f..2f) { v -> update("Contrast") { it.copy(color = it.color.copy(contrast = v)) } }
                Adjustment("Saturation", c.saturation, 0f..2f) { v -> update("Saturation") { it.copy(color = it.color.copy(saturation = v)) } }
                Adjustment("Temperature", c.temperature, -1f..1f) { v -> update("Temperature") { it.copy(color = it.color.copy(temperature = v)) } }
                Adjustment("Tint", c.tint, -1f..1f) { v -> update("Tint") { it.copy(color = it.color.copy(tint = v)) } }
                Adjustment("Sharpen", c.sharpen, 0f..1f) { v -> update("Sharpen") { it.copy(color = it.color.copy(sharpen = v)) } }
                TextButton(onClick = { update("Reset color") { it.copy(color = ColorAdjustments()) } }) { Text("Reset adjustments") }
            }
            "Mask" -> {
                Choices(MaskKind.entries.map { it.name }, clip.mask.kind.name) { kind -> update("Mask") { it.copy(mask = it.mask.copy(kind = MaskKind.valueOf(kind))) } }
                Adjustment("Feather", clip.mask.feather, 0f..1f) { v -> update("Feather") { it.copy(mask = it.mask.copy(feather = v)) } }
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Text("Chroma key", Modifier.weight(1f)); Switch(clip.chromaKey.enabled, { v -> update("Chroma key") { it.copy(chromaKey = it.chromaKey.copy(enabled = v)) } }) }
                Choices(listOf("Green", "Blue", "Black")) { name -> update("Key color") { it.copy(chromaKey = it.chromaKey.copy(color = when(name) { "Green" -> 0xFF00FF00; "Blue" -> 0xFF0000FF; else -> 0xFF000000 })) } }
                Adjustment("Similarity", clip.chromaKey.similarity, 0f..1f) { v -> update("Similarity") { it.copy(chromaKey = it.chromaKey.copy(similarity = v)) } }
                Adjustment("Smoothness", clip.chromaKey.smoothness, 0f..1f) { v -> update("Smoothness") { it.copy(chromaKey = it.chromaKey.copy(smoothness = v)) } }
            }
            "Transition" -> {
                Choices(TransitionKind.entries.map { it.name }, clip.transition.kind.name) { kind -> update("Transition") { it.copy(transition = it.transition.copy(kind = TransitionKind.valueOf(kind))) } }
                Adjustment("Duration", clip.transition.durationUs / 1e6f, .1f..2f, "s") { v -> update("Transition duration") { it.copy(transition = it.transition.copy(durationUs = (v * 1e6).roundToLong())) } }
                Text("Transition blends into this clip. Main-track clips automatically overlap for the chosen duration.", Modifier.padding(16.dp), color = Muted, fontSize = 12.sp)
            }
            "Keyframes" -> {
                Text("Transform at ${timeLabel((cursor - clip.startUs).coerceAtLeast(0))}", Modifier.padding(16.dp), color = Muted)
                Choices(listOf("Add / replace", "Remove nearest", "Clear all")) { action -> update(action) { c -> val local = (cursor - c.startUs).coerceIn(0,c.durationUs); when(action) { "Add / replace" -> c.copy(keyframes = (c.keyframes.filter { kotlin.math.abs(it.timeUs - local) > 33_333 } + Keyframe(local,c.transformAt(local))).sortedBy { it.timeUs }); "Clear all" -> c.copy(keyframes = emptyList()); else -> { val nearest = c.keyframes.minByOrNull { kotlin.math.abs(it.timeUs - local) }; c.copy(keyframes = c.keyframes - listOfNotNull(nearest).toSet()) } } } }
                Text("Set the playhead, adjust Transform, then add a keyframe. Position, scale, rotation and opacity interpolate between points.", Modifier.padding(16.dp), color = Muted, fontSize = 12.sp)
                Text(clip.keyframes.joinToString("   •   ") { timeLabel(it.timeUs) }.ifEmpty { "No keyframes" }, Modifier.padding(horizontal = 16.dp), color = Accent, fontSize = 12.sp)
            }
        }
    }
}
