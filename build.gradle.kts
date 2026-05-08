import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
}

group = "com.audiorouter"
version = "1.0.0"

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // StatusNotifierItem-based tray for KDE Plasma 6 / Wayland (replaces AWT XEmbed tray)
    implementation("com.dorkbox:SystemTray:4.4")

    // JNA for Windows WASAPI audio backend
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.13")
}

compose.desktop {
    application {
        mainClass = "com.audiorouter.MainKt"

        // Suppress native-access warning from Skiko on JDK 21+
        jvmArgs += "--enable-native-access=ALL-UNNAMED"

        nativeDistributions {
            targetFormats(TargetFormat.Rpm, TargetFormat.Deb, TargetFormat.AppImage, TargetFormat.Dmg)
            packageName = "AudioRouter"
            packageVersion = "1.0.0"
            description = "Per-application virtual audio routing"
            vendor = "AudioRouter"

            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }

            macOS {
                iconFile.set(project.file("src/main/resources/icon.icns"))
                bundleID = "com.audiorouter.app"
                // Microphone permission required for level capture via TargetDataLine
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>AudioRouter captures audio device output to display VU meter levels.</string>
                    """.trimIndent()
                }
            }
        }
    }
}

// Forward display/auth env vars to the run task — must be afterEvaluate
// so the Compose plugin has already registered the "run" task
afterEvaluate {
    tasks.named<JavaExec>("run") {
        listOf("DISPLAY", "XAUTHORITY", "WAYLAND_DISPLAY", "XDG_RUNTIME_DIR").forEach { v ->
            System.getenv(v)?.let { environment(v, it) }
        }
    }
}
