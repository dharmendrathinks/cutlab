# Original implementation contract

This document records the original local-editor milestone. Public-source distribution is now in scope; signed binary/store distribution remains future work. See [roadmap](ROADMAP.md) for current priorities.

CutLab is an independently implemented Android editor for local editing. The fixed behavioral reference is CapCut 13.6.0 (13601600), identified in `evidence/reference.json`. The target is close visual and behavioral parity, not an assertion of byte-identical application or output.

## Deliverables

- Native Kotlin/Compose Android app, API 29+, installed as `com.dhasharma.cutlab`.
- Local project list/import/edit/save/reopen/export workflow, command history and portable own-format archives.
- Trimming, splitting, reordering, overlays, text, audio, masks/chroma, transforms/keyframes, speed/reverse/freeze, color adjustments and the defined transition set.
- Shared timeline/rendering semantics for preview and H.264/AAC MP4 export at 720p/1080p and up to 60 fps.
- Tests, reproducible APK build, analysis provenance and explicit coverage/gap report.

## Boundaries

No CapCut server implementation or project format is included. AI, downloaded catalogs, accounts, cloud, 4K/HDR, and distribution are future work. The app must not connect to CapCut services. Proprietary binaries and copied implementation source are not application dependencies.

## Completion gates

1. Baseline: runnable original, fixed reference fixtures and source-linked findings.
2. Engine: composition, audio mixing, overlapping clips and export/preview correspondence.
3. Basic editor: import → edit → save → reopen → export.
4. Advanced manual tools: visible controls produce persisted/rendered changes.
5. Reference fidelity: screenshots, output timing within one frame, A/V sync within 20 ms, fixture image comparisons.
6. Device: physical Android validation of playback/performance, cancellation and recovery. Emulator tests do not close this gate.

Never equate implemented controls with verified parity. Track implementation, emulator validation, reference comparison and physical validation independently.
