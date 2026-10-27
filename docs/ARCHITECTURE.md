# Architecture and project format

`core` is a JVM-only module containing the serializable project model, timeline mapping, edit operations, command history and atomic JSON persistence. `app` supplies Android storage, codecs, OpenGL and Compose UI.

## Time and editing

All timeline and source times are integer microseconds. Clips have a timeline start, source in/out bounds, constant speed or piecewise-linear source-space speed curve, and optional reverse/freeze. Curve duration integrates reciprocal speed; source lookup inverts that integration. Transform keyframes interpolate over clip-local timeline time.

The primary video sequence can ripple and overlap for incoming transitions. Overlay, audio and text tracks retain explicit positions. Undo/redo keeps up to 100 immutable project snapshots. Imported media remains available when edits are undone.

## Rendering and audio

`TimelineRenderer` owns GLES resources and runs one shared scene composition for preview and encoder-surface export. Preview video frames use persistent, seekable MediaCodec decoders (at most three source instances). YUV planes are converted using range/BT.601/BT.709 metadata, sampled for preview size, and rotated using source metadata. Two adjacent decoded frames support nearest-frame selection. Backward or large jumps seek to a sync frame and flush the decoder. Unsupported output falls back to MediaMetadataRetriever for that source; encoder-surface export continues to use the established retriever path. Images use a bounded cache and preview source frames use a 16 MB LRU cache keyed by source, time and size. Repeat scrubs, frozen clips and edit redraws can reuse frames. CPU readback buffers are reused across frames. CPU preview frames require readback, while export draws directly to the H.264 encoder surface. The export thread owns its EGL context and releases it on success, error or cancellation.

The compositor supports transforms, rectangular crop, opacity, text, five mask shapes, chroma key, six adjustments, eight local filter presets, five shader effects and a fixed transition set. These are CutLab implementations; their equations are not recovered CapCut algorithms.

`AudioMixer` decodes source audio to temporary PCM files, mixes stereo at 48 kHz and maps source time using the same clip timing functions as video. Speed changes also change pitch. Freeze clips are silent. `AudioPreview` uses the same mixer through AudioTrack. When audio is available, its playback-head position drives the preview clock; silent projects use elapsed time. Preview samples the current time rather than blocking audio to display every source frame. Video export encodes AAC, combines streams, and publishes a completed file only after successful completion.

AAC export measures encoder priming with a short deterministic impulse when usable delay metadata is absent. The result is cached for the codec/configuration/OS identity within the process. Extra tail samples flush delayed sound; a bounded MP4 edit-list rewrite trims the presentation without discarding decoder warmup packets. Only CutLab's newly generated, nonfragmented output is rewritten, and media chunk offsets remain unchanged. This addresses the observed platform encoder behavior rather than assuming all encoders have the same delay. Android documents [encoder-delay metadata](https://developer.android.com/reference/android/media/MediaFormat#KEY_ENCODER_DELAY); the corresponding [Android 15 AAC encoder source](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android15-release/media/codec2/components/aac/C2SoftAacEnc.cpp) informed the investigation.

## Storage

`files/projects/<id>/project.json` is schema version 1. Source media is copied to that project's `media/` directory. Saves write and fsync a temporary file, preserve one valid previous checkpoint, then atomically replace the main file. Autosave is debounced; backgrounding flushes the latest editor state. Loading falls back to the prior valid checkpoint if the primary JSON is damaged.

Project cover time, canvas background and clip effects have backward-compatible defaults within schema 1. Startup recovery includes app-owned pending MediaStore rows and known temporary export prefixes; completed exports are retained. Export cancellation keeps cleanup and UI-state restoration within a non-cancellable scope.

Portable archives contain `project.json` and relative `media/` paths. Import creates a new project identity, checks paths and metadata size, and copies into a staging directory before publishing. Missing-media references survive archive round-trips. The format is intentionally independent of CapCut drafts.

## Validation boundaries

JVM tests cover timing, edits, history and recovery. Android tests exercise actual codecs, GL, archive/media storage, accessible UI navigation, timeline previews, output parity and cancellation. Diagnostic exports enable a separate FFmpeg check. A short synthetic fixture cannot prove sustained playback, arbitrary codec support, thermal behavior or complete reference fidelity; those remain separate validation gates.
