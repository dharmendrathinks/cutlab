# Development

## Toolchain

| Component | Version |
| --- | --- |
| JDK | 21 |
| Gradle wrapper | 8.11.1 (distribution checksum pinned) |
| Android Gradle Plugin | 8.10.1 |
| Kotlin / Compose compiler plugin | 2.1.20 |
| Compile / target SDK | 36 |
| Build Tools | 35.0.0 |
| Minimum Android | 10 / API 29 |

Install Android Studio or Android command-line tools. With `sdkmanager` on PATH:

```sh
sdkmanager 'platform-tools' 'platforms;android-36' 'build-tools;35.0.0'
```

Accept the SDK licenses when prompted. Set `ANDROID_HOME` to your SDK directory and `JAVA_HOME` to JDK 21. Alternatively, create `local.properties` containing `sdk.dir=/absolute/path/to/your/sdk`; this file is ignored. On macOS the helper uses Android Studio's bundled JDK if `JAVA_HOME` is unset; that JDK must support Java 21.

```sh
./scripts/gradle.sh --version
./scripts/check.sh
./scripts/gradle.sh :app:installDebug
```

Windows users can invoke equivalent tasks with `gradlew.bat`. Python 3.9+ is used by repository checks. FFmpeg/ffprobe are optional for independent export comparisons, not runtime dependencies.

## Layout

```text
app/src/main/             Compose UI, project storage, Android codecs and GLES
app/src/androidTest/      Device tests and the synthetic video fixture
core/src/main/            Serializable model, timeline math and edit operations
core/src/test/            Platform-independent regression tests
docs/                     User, architecture and contributor documentation
evidence/                 Sanitized historical measurement summaries
scripts/                  Build, validation and comparison helpers
```

Open the root folder in Android Studio and select the `app` run configuration. The application ID is `com.dhasharma.cutlab`. The project uses standard Gradle dependency repositories; a fresh build needs Internet access, while the installed editor does not.

## Working conventions

Use immutable model updates through editor operations. Keep time units explicit (`Us` for microseconds); avoid floating-point accumulation for frame navigation. Scope decoder, bitmap, GL and audio resources to their owners. Maintain source-media ownership and atomic save/recovery semantics. Review the [architecture](ARCHITECTURE.md) before changing these contracts.

There is no automatic whole-repository formatter yet. `.editorconfig` records defaults; keep changes focused and avoid mass reformatting existing compact Kotlin code.

## Troubleshooting

- **SDK not found:** set `ANDROID_HOME` or `local.properties`; do not commit your SDK path.
- **Java toolchain mismatch:** verify `JAVA_HOME` and `./scripts/gradle.sh --version` both point to JDK 21.
- **Install signature mismatch:** use the same signing key. Export archives before uninstalling a differently signed build; uninstall removes private projects.
- **Emulator memory pressure:** close unused emulators, stop idle Gradle workers with `./scripts/gradle.sh --stop`, and retry on a responsive emulator. Tests should not be weakened to hide host failures.
- **Unsupported export:** choose 720p/30 or 1080p/30 and attach codec/device details to a report. 1080p60 is capability-dependent.
