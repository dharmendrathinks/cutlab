# CutLab implementation and validation status

Updated 2026-10-22 on the Android 15/API 35 arm64 emulator, Pixel 7 profile, SwiftShader and software codecs. This is a runnable independent local editor. It is **not a completed exact CapCut replica**; whole-app reference fidelity and physical-device performance remain open gates. No physical device was available for that validation.

## 0.4.0 local-tool and emulator completion pass

- Timeline scrubs and trims use the export frame grid. Dragging snaps either clip edge; primary video drops reorder the sequence while overlays retain absolute timing. Drag/trim handles scroll at viewport edges. The time counter opens frame, cut and selected-key navigation.
- Cover selection renders the actual project composition at a saved cover time, including text-only projects. Canvas ratio controls include five background colors.
- Freeze inserts a three-second silent still without discarding surrounding video. Following clips and their transition overlap shift together; other tracks retain explicit positions. Transform-panel edits now preserve keyframes at the playhead.
- Eight local filter presets, five optional effects (mono, sepia, vignette, mosaic, soft focus), diamond/mirror masks and vertical slide/spin transitions use the shared compositor. These are finite, independently implemented catalogs.
- Export has dedicated progress/cancel and saved-video screens with Android play/share actions. Cancellation removes partial files and restores the editor. Startup recovery removes app-owned pending MediaStore entries and known intermediate export files, while preserving completed videos.
- Preview uses a bounded 16 MB source-frame cache, a 640-pixel long edge and the AudioTrack output clock when audio is available. A persistent MediaCodec decoder now advances through frames without restarting for every request, with nearest-frame seeking, rotation and color conversion checks. Unsupported decoder output falls back to MediaMetadataRetriever; export retains its established path. Readback buffers are reused and playback pacing accounts for render time.

Validation: **18 JVM tests passed; the full Android suite passed 29 tests with one 1080p60 capability skip and zero failures** (142.510 seconds). Build and lint pass. The 900-frame preview benchmark has a **12.1 ms median, 43.1 ms p95**, 90.6% of frames below 33.333 ms, no extractor fallback and at most 16 MB of cached source frames. The final APK additionally passes **3 focused Android checks in 30.057 seconds**, including actual painted export headings/Done text, cancellation/retry, trim/undo and text gesture/style/export correspondence. Earlier final-check attempts encountered emulator System UI ANRs under host memory pressure; this passing run followed stopping the task's build workers and rebooting the emulator.

The packaged app’s fixed color-bar editor was remeasured: preview edges remain within **3 pixels** and clip edges within **1 pixel** of the reference. See `artifacts/completion-layout-comparison.html` and `evidence/completion-layout-comparison.json`; these are four landmarks, not a whole-app similarity score.

Current verification and performance measurements are recorded in `evidence/completion-validation.json` and `evidence/completion-preview-benchmark.json`. The complete Android suite is rerun for this release, including earlier engine/storage/UI checks. Dedicated UI coverage includes exact one-second frame stepping, cover save/reopen, actual long-press reordering, cancellation without publication, subsequent successful export, and probing the resulting shared video. Screenshot evidence covers progress, saved-video and editor screens.

Phone validation remains **not performed**. The software-emulator render benchmark does not establish sustained 30 fps, hardware behavior or thermal performance. Full catalog/gesture/effect parity remains unverified. See [completion checklist](COMPLETION.md) for the precise handoff boundary.

## 0.3.0 preview manipulation and text

- Tap visible layers to select them; drag to reposition, pinch/twist to scale/rotate, or use the corner handle. Hit testing follows rotated, cropped bounds in the fitted project canvas, including letterboxing. Center/90-degree snapping uses the existing snapping toggle.
- Gesture drafts update the shared preview renderer without filling undo history. A completed gesture commits once; cancel restores the committed state. Existing transform keys are preserved, with a key inserted/replaced at the playhead.
- Text has Fonts, Style and Animation tabs: four local font families, regular/bold/italic, alignment, size, seven colors, outlines, shadows and backgrounds. Multi-line text wraps to canvas width. Text layout metrics are shared by preview selection and rendering.
- Fade, Pop and Slide up entrance/exit animations share the export transform evaluation and clamp their durations for short clips. Existing project files receive defaults for new fields.
- The expanded text panel reserves visible timeline space by reducing preview padding/height. The main editor's geometry remains unchanged.

Validation: **14 JVM tests and 5 focused Android tests passed**. Real one-finger and two-finger gestures, one-step undo/redo, font/outline styling, save/reopen and decoded H.264 export correspondence are covered. The final device run took 24.537 seconds; the full older engine/storage suite was not rerun. Validation results and artifacts for this pass are recorded in `evidence/preview-text-validation.json`. Only the API 35 emulator was connected; physical-device performance, touch ergonomics and codec behavior remain unverified. These tools are independently implemented and do not establish exact CapCut gesture curves, font catalogs or whole-screen parity. The subsequent 0.4.0 pass adds timeline refinements and dedicated export screens; their full reference parity remains unverified.

## 0.2.0 reference UI pass

The home/project list and local import → edit → text/audio → export-settings workflow were compared directly with the installed CapCut 13.6.0 reference on the same 1080×2400 emulator. This pass changes the UI and timeline thumbnail selection; the media exporter/compositor algorithms are unchanged.

- Home now has a centered new-project card, real project covers and compact metadata rows. New project opens Android media selection immediately and normalizes the first source's aspect ratio to a 1080-short-edge editing canvas. Blank projects and the demo remain separate actions.
- Editor uses the measured preview/timeline proportions, centered playback, matching dark surfaces, a rectangular cyan export button and an upper resolution/frame-rate panel. Opening a tool keeps the preview in place and reduces timeline space.
- Clip selection changes the bottom toolbar. Main, text, audio and overlay actions have separate menus. White trim handles update real source boundaries through the existing edit/history/persistence pipeline. Timeline pinch zoom and the snapping toggle replace the visible zoom slider.
- Timeline clips are taller, use center-cropped moving thumbnails and hide file-name overlays on video. Thumbnail cache keys include reverse, speed curves and freeze state. A text track no longer sits underneath the Add audio placeholder.
- New text clips use the remaining video duration, capped at three seconds, instead of extending a short video by default. Text entry has a checkmark to apply changes. Project naming and ZIP export remain in the editor's options menu.

The fixed, unselected color-bar editor comparison measures the preview edges within **3 pixels** vertically and the primary clip edges within **1 pixel** of the reference; before this pass the offsets were 80 and 99–133 pixels respectively. These are four geometry landmarks on one device/state, **not a percentage of overall app similarity**. The script `scripts/compare-editor-layout.py` reproduces the measurement from the local screenshots. The embedded before/reference/after visual report is retained in `artifacts/ui-layout-comparison.html`.

Validation for this pass: **2 final Android workflow tests passed**, covering actual drag-to-trim persistence, undo, text input/apply/save/reopen, preview-control placement, visible resolution-label pixels and playhead repainting after export settings. **3 media checks passed** in the preceding focused run, including moving/reversed thumbnail cache behavior and real audio waveforms. Final workflow results are in `artifacts/ui-match-tests-final.txt`; the earlier `artifacts/ui-match-tests.txt` retains the workflow failure that prompted the fixes along with the passing media checks. A manual export from the upper settings panel also completed into Movies/CutLab and was probed as H.264/AAC MP4. The old full engine/storage suite below was not rerun for this UI pass.

Regression fixes found during this pass: trim handles sit above the add-media button’s expanded touch area; navigation/autosave read the authoritative history state; preview disposal releases decoder/GL resources off the UI thread; returning home refreshes the saved project list before showing it; one persistent header prevents the resolution label disappearing when export settings open or close. Gesture tests wait for rendered frames and completed navigation before checking saved files. Cold software-emulator timing remains variable and is not a physical-device performance certification.

Remaining UI differences include the Android system media picker, original icons/branding, reduced tool/catalog breadth, the exact text/font/style controls, gesture curves, cover/export layouts and some panel dimensions/animations. No unsupported cloud/catalog buttons were added merely to resemble the reference. Full CapCut parity is still open.

## Implemented

| Area | Available behavior |
| --- | --- |
| Projects | New/list/rename/delete, copied source media, autosave, previous-checkpoint recovery, portable archive import/export, demo project |
| Timeline | Trim/split/duplicate/delete/reorder, primary-track ripple and transition overlap, manually positioned overlays, zoom/scrub, thumbnails, audio waveforms, undo/redo |
| Video | Position/scale/rotation/opacity, flips/crop, transform keyframes, constant speed and editable preset curves, reverse, freeze |
| Composition | Video/image overlays, plain text, rectangle/ellipse/linear/diamond/mirror masks with feather, green/blue/black chroma key, exposure/contrast/saturation/temperature/tint/sharpen |
| Transitions | Dissolve, black, white, slide left/right/up/down, wipe, zoom and spin; both clips advance during overlap |
| Audio | Mono/stereo mixing, source audio/extraction, volume/mute/fades, file import, microphone recording controls, preview playback |
| Export | Shared GLES scene for preview and H.264/AAC MP4 export, 720/1080 short-edge resolution, 24/25/30/50/60 fps subject to encoder capability, cancellation/temporary-file cleanup, Movies/CutLab publication |

## Initial engine/storage validation (0.1.0)

- JVM: **11 tests passed**, covering timing/curve integration, reverse/split/trim, keyframes, transition reflow, history and checkpoint recovery.
- Android instrumentation: **15 passed, 1 capability-skipped, 0 failed**. The runner prints `OK (16 tests)`; its explicit assumption result skips 1080p60 because both emulator AVC encoders reject that size/rate combination. It is not counted as a pass.
- Debug APK build, instrumentation compilation and Android lint passed. Lint retains advisory dependency/style/storage warnings; no lint errors.
- UI workflow checks text edits, undo/redo, save/reopen/delete, and actual playhead pixels after export-dialog dismissal. Timeline tests decode real thumbnails and a silence/tone waveform.
- Storage tests cover owned video import, portable archive recovery, missing-media references, traversal rejection and oversized-metadata cleanup.
- Engine tests use actual Android codecs and GLES: audible export, moving-video dissolve, alpha/chroma composition, 1080p30 portrait geometry, preview/export correspondence, cancellation, and 90-frame baseline export.
- A/V test exports flashes with audio pulses at 0, 250, 750 and 958.333 ms. Re-imported pulse offsets are within **21 microseconds** on this fixture. Independent FFmpeg decoding found offsets within **42 microseconds**, with the final pulse still audible after 987 ms. This is a short export-timing check, not a guarantee of live playback sync on every device.
- Manual checks: Android file-picker video import, changing canvas ratio, save/reopen, and an eight-second demo export through the app into Movies/CutLab.

Machine-readable results are in [validation.json](../evidence/validation.json), [A/V measurements](../evidence/engine-av-sync.json), [independent FFmpeg measurements](../evidence/ffmpeg-av-sync.json), and [encoder capabilities](../evidence/engine-codec-capabilities.json). Local full test logs and diagnostic videos are retained in ignored `artifacts/`.

## Reference comparison

Both CapCut and CutLab exported the unchanged three-second fixture as 90 frames of 1920×1080 H.264 at 30 fps, with video duration exactly 3.000 s. FFmpeg SSIM at 640×360 is approximately **0.959**. This measures only that baseline output; it does not verify advanced tools or complete UI fidelity. See [baseline-comparison.json](../evidence/baseline-comparison.json) and [reference findings](REFERENCE_FINDINGS.md).

The editor adopts the observed neutral dark palette, cyan export, large preview, centered playhead and bottom tool strip; home uses a light background and cyan/blue card. The 0.2.0 pass above measures a small set of editor geometry landmarks. Other layouts, gestures, typography, controls and effect equations still differ; no whole-screen or whole-app parity result is claimed.

## Performance and remaining limits

- A 0.3-second 1080p30 portrait export took 655 ms in the final synthetic benchmark. Median preview render time was 27 ms for the small source and 47 ms for an upscaled 1080 source rendered at 360×640. These short software-emulator samples do not meet or establish sustained device playback targets.
- Preview uses persistent MediaCodec decoding with platform extraction fallback; export still uses MediaMetadataRetriever. The 0.4.0 pass measures a 30-second/900-frame preview and bounds the frame cache. This synthetic emulator measurement does not certify real-device throughput, thermals or arbitrary projects. Proxy generation and physical hardware audio/video sync remain outside the verified result.
- 1080p60 is selectable and capability-checked, but could not be validated on this emulator. A supporting physical Android device is required. Microphone quality and device-specific codecs also remain unverified.
- Text uses four Android system font families with local outline/shadow/background styling and three entrance/exit animations. The full CapCut font/effect/transition/mask catalog is not implemented. Speed changes alter audio pitch; freeze clips are silent. Audio is limited to mono/stereo.
- Splitting within an active incoming transition is rejected with an actionable message. Remove the transition or split outside its overlap. Freeze insertion now creates a separate silent still segment; insertion inside an active incoming transition has the same explicit split restriction.
- AI, accounts, cloud, downloadable catalogs, CapCut draft compatibility, 4K/HDR and distribution are outside this local-first implementation.

## Public repository preparation

The initial local handoff had no commits or remote publication. The source is now being prepared for public contribution under MIT with CI, contributor documentation and release guidance. Historical runtime measurements above remain unchanged. See [development](DEVELOPMENT.md), [testing](TESTING.md) and [releasing](RELEASING.md) for the current public workflow. Local PR-readiness reports are retained in ignored `artifacts/`; they are not a runtime certification.
