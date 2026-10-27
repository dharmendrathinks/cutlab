# Contributing to CutLab

Thanks for helping improve an independent Android editor. Bug reports, device testing, documentation, accessibility work, and code contributions are welcome.

## Start here

Read the [development guide](docs/DEVELOPMENT.md) and [architecture](docs/ARCHITECTURE.md). Check existing issues before opening one. For a substantial feature or dependency, open an issue describing the user problem and proposed approach before investing in a large patch. Small fixes can go straight to a pull request.

Good first contributions include reproducible device reports, clearer error messages, accessibility labels, and focused tests for timeline edge cases. The [roadmap](docs/ROADMAP.md) records priorities without promising dates.

## Local workflow

1. Fork the repository and create a branch from `main`.
2. Make one coherent change. Follow the surrounding Kotlin style; avoid unrelated formatting churn.
3. Run `./scripts/check.sh`. Changes to codecs, storage, rendering, gestures, or UI also need the relevant Android tests; see [testing](docs/TESTING.md).
4. Open a pull request describing the problem, changed behavior, and exact validation. Include before/after screenshots for visible UI changes using synthetic or freely shareable media.

Keep time values in integer microseconds, preserve immutable edit/history behavior, and keep blocking media/disk work off the main thread. Changes to project serialization need compatibility and archive round-trip coverage. Renderer changes should verify both preview and decoded exports. Do not replace an unsupported codec assumption with a silent pass.

Do not commit signing keys, local SDK paths, APKs, private media, copied proprietary assets, or generated build output. Synthetic fixtures should have a documented origin and a small size. New dependencies need a clear purpose, license information, and an update to the notices when appropriate.

## Review and licensing

The maintainer reviews scope, correctness, tests, compatibility, and user-facing behavior. CI passing is necessary but does not replace review. No separate contributor license agreement is required. By submitting a contribution, you agree to license your original contribution under the repository's MIT License and confirm you have the right to submit it. Preserve applicable third-party notices.

Be considerate and follow the [Code of Conduct](CODE_OF_CONDUCT.md). For vulnerabilities, use [private security reporting](SECURITY.md) rather than a public issue.
