# Reference investigation

## Fixed artifact

CapCut Android 13.6.0, versionCode 13601600, package `com.lemon.lvoverseas`. APK and signer digests are recorded in `evidence/reference.json`. The official website download was used. ZIP CRC and Android APK signature verification passed. The signer certificate was observed, not compared with an independently pinned certificate.

REA is cloned in the sibling `rea` directory at commit `d4c637f4c`. Investigation used the published `rea-agents@6.7.0` CLI and JADX headless 0.7.1, with JDK 21 and a 6 GiB heap. A first 2 GiB attempt exhausted memory; the successful retry replaces that incomplete observation.

## Static observations from REA

| Observation | Evidence ID |
| --- | --- |
| Package identity; 126,107 parsed classes and 27,633 resources | `ev_17f1f98db30b4873da4af3d2d75927662312906f70126e5a5e69f345de9266bb` |
| No parsed class matching the speculative `NLETimeSpaceNode` query | `ev_27bcf6a306a069bf6e475c66c4615928cbc26716e5a80be44c0dda2e1bbde41c` |
| `com.ss.android.vesdk.clipparam.VEClipTimelineParam` has `trimIn`, `trimOut`, `speed`, `curveSpeedPointX`, `curveSpeedPointY`, and `isReverseCurveSpeed` fields | `ev_61781d2465c4e9fbcd34d6f2e511af8b461d9c8525a3934ed05d5d3ecb89748f` |

The class fields establish that these concepts exist in the shipped artifact. They do **not** establish time units, curve interpolation, rendering equations, or runtime behavior. CutLab's time mapping and compositor are independent implementations; their formulas are not claimed to reproduce private algorithms.

Separate ZIP/DEX inventory found 24 DEX files, 147 arm64 libraries and 145 armv7 libraries. Large components include `libmiddle-bridge.so`, `libmiddleware.so`, `libeffect.so`, `libres_pool.so`, `libttvesdk.so`, `libttffmpeg.so`, and `libaudioeffect.so`. This suggests a substantial native engine, but library names alone do not prove call paths. Ghidra 12.1.4 was installed and its arm64 macOS decompiler built; REA reports it available. No native decompilation result is used as implementation evidence here.

## Runtime observations

Reference runs on the API 35 arm64 Google APIs emulator, Pixel 7 profile, 1080×2400 display. The reference was opened without an account. A synthetic three-second video with audio was imported.

- Home uses a light background and cyan/blue new-project card.
- Editor uses neutral dark backgrounds, cyan export, a large preview, centered playhead, thumbnail timeline, and a bottom tool strip.
- Import adds a two-second ending in this installation. The ending was selected and deleted before the baseline export.
- The reference baseline export at the displayed default 1080P produces 1920×1080 H.264 at 30 fps, video duration 3.000 s; AAC is stereo at 44.1 kHz, duration approximately 3.019 s.

Local raw captures are retained under ignored `evidence/raw/`: package/class REA JSON, editor screenshots, and reference output video. They are not bundled in the app. The shared synthetic fixture is `app/src/androidTest/assets/reference.mp4`, SHA-256 `d3a2f9b4a074472428dc05fb1313ae93855bf757197f074e046f818894be2c84`.

## Evidence limits

One baseline export and editor screen do not establish parity across all tools. CapCut source code, assets, services and binaries are not dependencies of CutLab. No claim is made that server features, licensed catalogs, proprietary effects, advanced motion interpolation, text shaping, or audio processing were recovered.

## Additional UI observations (0.2.0 pass)

The same installed reference was inspected in the unselected editor, selected video clip, text submenu, text entry and resolution panel states. The unselected editor's preview occupies y=471…1078 and its primary clip row y=1578…1708 at 1080×2400. Selecting a clip exposes white trim handles and a context toolbar; text opens a submenu before text entry. The resolution selector expands a dark panel below the header while dimming the editor. These are runtime observations of this version/device, not universal dimensions for every CapCut installation.

Screenshots are retained locally as `evidence/raw/capcut-match-*.png`. CutLab matches the core layout and local actions independently; its system media picker, font catalog, advanced panels and export completion flow remain different.

## 0.4.0 completion-flow observation (2026-10-22)

Reopened the retained three-second reference project in CapCut 13.6.0 and exported it again locally. The saved-video screen has a black background, top back/Done controls, “Ready to share” above a centered video thumbnail, and a large white share action beneath it. CutLab adopts this structure and spacing, with a generic Android share chooser and local playback action. CapCut’s TikTok/Instagram/WhatsApp/Facebook shortcuts, branding and integrations are not implemented. The screenshots are `evidence/raw/capcut-completion-editor.png` and `evidence/raw/capcut-completion-saved.png`; no external sharing was initiated.

CutLab’s export progress, cancel confirmation, cleanup, successful MediaStore publication and saved-video return flow are exercised through instrumentation. This verifies the local workflow, not exact provider integrations or pixel-identical full screens. The main editor’s fixed-fixture geometry and output comparison remain separate measurements.
