# Release process

There is no production-signed release yet. Version 0.4.0 describes the current implementation milestone. CI produces a development APK signed with the runner's temporary debug identity; it is not an update channel.

## Prepare

1. Review open bugs and [device validation gates](TESTING.md). Record the exact commit, toolchain, test results and known limitations.
2. Update `versionCode`/`versionName` in `app/build.gradle.kts` and move relevant changelog entries into a dated version section.
3. Run `./scripts/check.sh` and the full Android suite on the intended device matrix. Retain the reports, decoded export checks and capability skips.
4. Review runtime dependency licenses/notices before distributing a binary. Run `./scripts/gradle.sh :app:dependencies --configuration releaseRuntimeClasspath` to inspect the resolved graph. Dependency source licenses do not all become MIT.

## Build and sign

```sh
./scripts/gradle.sh :app:assembleRelease
```

The output is `app/build/outputs/apk/release/app-release-unsigned.apk`. Release builds deliberately have no signing configuration. Use Android Studio's **Generate Signed Bundle / APK** workflow or Android SDK `zipalign`/`apksigner` with a securely stored release key. Never commit a keystore/password or use a debug key for a production release. Verify the signature and install/upgrade behavior before publishing.

A release key must remain stable for future updates. Changing keys can require an uninstall, which deletes private projects. Test upgrade compatibility and archive recovery with the previous signed version.

## Publish

Create a reviewed version tag and a GitHub Release containing the signed APK, SHA-256 checksum, source commit, device/test matrix, changelog and known limits. Do not upload local research captures, personal media, debug keys or private paths. Store distribution and key management remain maintainer responsibilities; no automatic release upload workflow is enabled.
