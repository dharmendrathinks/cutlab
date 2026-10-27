# Historical validation evidence

These sanitized JSON records describe local development runs from October 2026. They preserve test counts, fixture hashes, observed reference behavior and performance measurements. They are not current CI results or a device compatibility guarantee.

Start with [completion-validation.json](completion-validation.json), [completion-preview-benchmark.json](completion-preview-benchmark.json) and [ffmpeg-av-sync.json](ffmpeg-av-sync.json). Referenced `artifacts/` and `evidence/raw/` paths are local-only captures deliberately excluded from the public repository. APK hashes identify historical local debug builds, not the output of a fresh build.

Absolute home paths in research metadata have been replaced with portable placeholders. The local REA environment inventory is excluded. Proprietary APKs, decompiled source, raw reference screenshots and exports are not distributed. The synthetic test fixture needed by the Android tests is included under `app/src/androidTest/assets/`.
