# Editing with CutLab

## Start a project

Choose **New project** to select local videos or images with Android's picker. The first source sets the canvas ratio. **Blank project** starts without media; **Try demo** creates a project with generated artwork. Each import is copied into that project's private media folder, so allow enough free storage for sources, intermediate files and exports.

Projects autosave locally. Return to the project list to reopen an edit. Use the editor's **…** menu to rename the project or export a portable `.cutlab.zip` archive. **Import project** restores an archive as a new project. An archive is the way to move editable projects between installations; an exported MP4 contains only the finished video.

## Use the timeline

- Scroll to scrub and pinch to zoom. The centered line is the playhead.
- Tap a clip to reveal its tools. Drag its white end handles to trim.
- Hold and drag to move a clip. Primary video clips reorder into a sequence; other tracks retain their own positions. Drag near an edge to scroll the timeline.
- Use Split at the playhead, or Duplicate/Delete on a selected clip. Undo and Redo apply to completed edits.
- Tap the time counter for exact frame stepping and navigation between cuts or transform keys.
- Use **Cover** to choose a project thumbnail from the current composition.

The snapping control affects clip-edge and preview alignment. Timing follows the chosen export frame grid. Splitting or inserting a freeze inside an active incoming transition is rejected; remove the transition or move outside its overlap first.

## Compose and style

**Overlay** adds image/video layers. Tap a layer in the preview, drag to position, pinch/twist to scale and rotate, or use the round corner handle. A completed gesture is one undo step. When a clip has transform keyframes, edits apply at the playhead.

**Text → Add text** opens the text editor. Apply typing with the checkmark. Fonts, Style and Animation tabs control local font families, size, color, alignment, outline, shadow, background, and entrance/exit behavior. Text wraps within the canvas.

Selected clips expose crop, transforms, speed/reverse, volume, masks, chroma key and related tools. **Trim** includes a three-second silent freeze insertion. **Adjust**, **Filters** and **Effects** change the local look; these are finite built-in catalogs. **Ratio** controls canvas proportions and background color. Transitions overlap adjacent primary clips and shorten the combined timeline accordingly.

## Audio

Import an audio file, extract source audio, or use the microphone recording controls. Recording requires Android microphone permission. Volume, mute and fades affect preview and export. Speed changes alter pitch; freeze segments are silent. Mono/stereo sources are supported; microphone quality and hardware playback sync need broader physical-device testing.

## Export and share

The **1080P** (or selected resolution) control opens export settings. Choose 720p/1080p and a frame rate supported by your encoder. Resolution describes the canvas short edge; for example, a 16:9 1080p project exports at 1920×1080. Export is SDR H.264 video with AAC audio in MP4.

Tap **Export** to begin. Cancel returns to the editor and removes partial output. Successful exports appear in `Movies/CutLab`, followed by play/share controls. Sharing opens Android's chooser; the receiving app may use the network. Export does not automatically send anything to a social platform.

## Media compatibility and recovery

JPEG and PNG still images are supported. Recognized video codecs include AVC/H.264, HEVC, MPEG-4 Visual, VP8 and VP9; actual decoding depends on Android and the device. Supported audio formats likewise depend on platform decoders. HDR sources, including supported detection of gain-map JPEGs, are rejected; use SDR input. Large or unusual files may exceed memory or codec limits even when their codec is recognized.

CutLab retains one previous valid project checkpoint and tries it if the primary JSON is damaged. Startup cleans up its interrupted export files while keeping completed videos. This recovery is not a substitute for backups.

Deleting a project removes its copied media. Original source files and completed exports remain. Uninstalling the app removes private projects: export project archives before changing signing identities or reinstalling. See [privacy](PRIVACY.md) for storage and sharing behavior.

For problems, report the app commit/version, device/API, source format, export settings and reproduction steps. Use synthetic examples and the [bug report form](https://github.com/dharmendrathinks/cutlab/issues/new?template=bug_report.yml).
