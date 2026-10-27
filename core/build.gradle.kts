plugins { kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { jvmToolchain(21) }
dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1"); testImplementation(kotlin("test")); testImplementation("junit:junit:4.13.2") }
