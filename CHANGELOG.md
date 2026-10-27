# Changelog

Notable changes are recorded here. Versions before 1.0 may change project behavior; compatibility changes will be called out explicitly. Dates below refer to implementation milestones, not store releases.

## Unreleased

- Publish the independent editor source under the MIT License.
- Add contributor, security, privacy, development, testing, and release documentation.
- Add CI, manual emulator testing, dependency updates, issue forms, and repository hygiene checks.
- Remove automatic debug signing from release builds; debug builds remain locally signed.
- Sanitize local machine paths in research metadata and retain raw research artifacts outside Git.

## 0.4.0 — 2026-10-22

- Add frame-aware navigation, cover selection, snapping, reorder and edge scrolling.
- Add freeze insertion, canvas colors, local filters/effects, masks and transitions.
- Add export progress, cancellation/retry, saved-video actions and interrupted-export recovery.
- Improve preview decoding with persistent codecs, bounded frame caching and an audio-driven clock.
- Validate the complete local workflow on an API 35 emulator; physical-device testing remains open.

## 0.3.0 — 2026-10-22

- Add direct preview manipulation, text styling and entrance/exit animations.
- Preserve transform keyframes and one-step undo across completed gestures.

## 0.2.0 — 2026-10-21

- Refine the project list, editor layout, timeline thumbnails, trim handles and tool navigation.

## 0.1.0 — 2026-10-21

- Implement local projects, timeline editing, the shared compositor, audio mixing, MP4 export and portable archives.
