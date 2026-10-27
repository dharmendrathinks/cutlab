# Third-party notices and provenance

The MIT License applies to original CutLab code and documentation. It does not replace the licenses of dependencies or imply ownership of third-party trademarks.

## Files distributed in this source repository

| Material | Origin / license |
| --- | --- |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` | Gradle 8.11.1 wrapper, copyright the original authors, Apache License 2.0; retained script headers and [license text](licenses/Apache-2.0.txt) |
| Android test video | Synthetic color bars/audio created for this project; MIT; digest in [testing](docs/TESTING.md) |
| Documentation screenshots | CutLab with synthetic content; MIT; [capture notes](docs/assets/README.md) |
| App demo art and launcher icon | Original programmatic/vector graphics included under MIT |

## Dependencies fetched by the build

The build uses [AndroidX / Jetpack Compose](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt), [Kotlin](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt), and [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization/blob/master/LICENSE.txt), under Apache License 2.0. Compose Material icons are supplied by AndroidX. Fonts are Android system fonts, not bundled font files.

Build/test tools include Gradle and Android tooling, AndroidX Test, and [JUnit 4](https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt) (Eclipse Public License 1.0). Their transitive dependencies retain their respective licenses. This is an overview of direct components, not a complete binary bill of materials. Review the resolved runtime graph and preserve applicable notices when distributing a compiled app; see [releasing](docs/RELEASING.md).

REA/JADX/Ghidra were external investigation tools, not runtime dependencies and not vendored here. FFmpeg is an optional external verification tool, not bundled or linked by CutLab. No CapCut binary, source, logo, font, effect catalog or server client is included. CapCut and other referenced product names belong to their owners; no affiliation or endorsement is implied.
