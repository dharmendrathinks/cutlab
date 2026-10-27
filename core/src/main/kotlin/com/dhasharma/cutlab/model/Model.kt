package com.dhasharma.cutlab.model

import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
@Serializable enum class AssetKind { VIDEO, IMAGE, AUDIO }
@Serializable enum class TrackKind { VIDEO, AUDIO, TEXT }
@Serializable enum class MaskKind { NONE, RECTANGLE, ELLIPSE, LINEAR, DIAMOND, MIRROR }
@Serializable enum class TransitionKind { NONE, DISSOLVE, BLACK, WHITE, SLIDE_LEFT, SLIDE_RIGHT, WIPE, ZOOM, SLIDE_UP, SLIDE_DOWN, SPIN }
@Serializable data class MediaAsset(val id: String = newId(), val path: String, val name: String, val kind: AssetKind, val durationUs: Long = 5_000_000, val width: Int = 0, val height: Int = 0, val hasAudio: Boolean = false)
@Serializable data class Transform(val x: Float = 0f, val y: Float = 0f, val scale: Float = 1f, val rotation: Float = 0f, val opacity: Float = 1f, val flipX: Boolean = false, val flipY: Boolean = false, val cropLeft: Float = 0f, val cropTop: Float = 0f, val cropRight: Float = 0f, val cropBottom: Float = 0f)
@Serializable data class Keyframe(val timeUs: Long, val transform: Transform)
@Serializable data class SpeedPoint(val position: Float, val speed: Float)
@Serializable data class ColorAdjustments(val exposure: Float = 0f, val contrast: Float = 1f, val saturation: Float = 1f, val temperature: Float = 0f, val tint: Float = 0f, val sharpen: Float = 0f)
@Serializable data class Mask(val kind: MaskKind = MaskKind.NONE, val feather: Float = 0f)
@Serializable data class ChromaKey(val enabled: Boolean = false, val color: Long = 0xFF00FF00, val similarity: Float = .2f, val smoothness: Float = .1f)
@Serializable data class Transition(val kind: TransitionKind = TransitionKind.NONE, val durationUs: Long = 500_000)
@Serializable enum class TextAnimation { NONE, FADE, POP, SLIDE_UP }
@Serializable data class TextStyle(val text: String = "Your text", val fontSize: Float = 56f, val color: Long = 0xFFFFFFFF, val background: Long = 0x00000000, val bold: Boolean = true, val alignment: String = "center", val fontFamily: String = "sans-serif", val italic: Boolean = false,
 val outlineColor: Long = 0xFF000000, val outlineWidth: Float = 0f, val shadowColor: Long = 0xCC000000, val shadowRadius: Float = 0f,
 val entrance: TextAnimation = TextAnimation.NONE, val exit: TextAnimation = TextAnimation.NONE, val animationDurationUs: Long = 300_000)
@Serializable enum class EffectKind { NONE, MONO, SEPIA, VIGNETTE, MOSAIC, SOFT_FOCUS }
@Serializable data class VideoEffect(val kind: EffectKind = EffectKind.NONE, val intensity: Float = 1f)
@Serializable data class Clip(
 val id: String = newId(), val assetId: String? = null, val startUs: Long = 0, val inUs: Long = 0, val outUs: Long = 5_000_000,
 val speed: Float = 1f, val speedCurve: List<SpeedPoint> = emptyList(), val reverse: Boolean = false, val freezeUs: Long? = null,
 val transform: Transform = Transform(), val keyframes: List<Keyframe> = emptyList(), val color: ColorAdjustments = ColorAdjustments(),
 val mask: Mask = Mask(), val chromaKey: ChromaKey = ChromaKey(), val transition: Transition = Transition(),
 val volume: Float = 1f, val fadeInUs: Long = 0, val fadeOutUs: Long = 0, val muted: Boolean = false, val text: TextStyle? = null, val effect: VideoEffect = VideoEffect()
)
@Serializable data class Track(val id: String = newId(), val name: String = "Video", val kind: TrackKind = TrackKind.VIDEO, val clips: List<Clip> = emptyList(), val muted: Boolean = false)
@Serializable data class ExportSettings(val height: Int = 1080, val fps: Int = 30, val bitrate: Int = 12_000_000)
@Serializable data class Project(val schemaVersion: Int = 1, val id: String = newId(), val name: String = "Untitled", val width: Int = 1080, val height: Int = 1920, val assets: List<MediaAsset> = emptyList(), val tracks: List<Track> = listOf(Track()), val export: ExportSettings = ExportSettings(), val modifiedAt: Long = System.currentTimeMillis(), val coverUs: Long = 0, val background: Long = 0xFF000000)
