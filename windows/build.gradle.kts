import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.serialization") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
}

group = "com.kar.reader"
version = "0.5.0"

kotlin { jvmToolchain(17) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.apache.pdfbox:pdfbox:3.0.5")
    // MP3 decoding for javax.sound (WAV is built in).
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")
    // Speech engine: Java API plus the Windows native libraries (downloaded by scripts/fetch-windows-speech.sh).
    implementation(files("libs/sherpa-onnx-jvm-1.13.6.jar", "libs/sherpa-onnx-native-lib-win-x64-1.13.6.jar"))
    testImplementation(kotlin("test"))
}

// The platform-independent core (EPUB parser, citations, spaced repetition, transcription logic...) is shared
// with the Android app: it is copied from there at build time, so there is exactly one copy of that logic.
val sharedCore = layout.buildDirectory.dir("generated/shared")
val syncShared = tasks.register<Sync>("syncShared") {
    from("../app/src/main/java") {
        include(
            "com/kar/reader/core/domain/*.kt", "com/kar/reader/core/model/*.kt", "com/kar/reader/core/io/*.kt",
            "com/kar/reader/core/zip/*.kt", "com/kar/reader/core/epub/*.kt",
        )
    }
    into(sharedCore)
}
kotlin.sourceSets.named("main") { kotlin.srcDir(sharedCore) }
tasks.named("compileKotlin") { dependsOn(syncShared) }

tasks.test { useJUnitPlatform() }

// Headless checks used by CI: reading books, and the real speech pipeline on this machine.
tasks.register<JavaExec>("smoke") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.kar.reader.desktop.SmokeKt")
    systemProperty("compose.application.resources.dir", layout.projectDirectory.dir("resources/common").asFile.absolutePath)
    environment("KAR_DATA_DIR", layout.buildDirectory.dir("smoke-data").get().asFile.absolutePath)
    args = (project.findProperty("smokeArgs") as String?)?.split(" ") ?: emptyList()
}

compose.desktop {
    application {
        mainClass = "com.kar.reader.desktop.MainKt"
        jvmArgs += listOf("-Xmx2g")
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "KAR READER"
            packageVersion = "0.5.0"
            vendor = "KAR READER"
            description = "Offline PDF and EPUB reading, research notes and speech transcription."
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            modules("java.sql", "jdk.unsupported", "java.naming", "java.desktop", "java.management", "java.net.http", "jdk.crypto.ec")
            windows {
                menuGroup = "KAR READER"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "5b3f8f40-6c7d-4d57-9a47-0c1d2f6e7a11"
                iconFile.set(project.layout.projectDirectory.file("icon.ico"))
            }
        }
    }
}
