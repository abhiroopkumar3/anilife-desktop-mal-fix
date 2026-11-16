import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.WriteProperties

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
}

repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
    maven("https://repo.bitsquidd.xyz/repository/bit/")
}

kotlin {
    jvmToolchain(21)
}

val osName = System.getProperty("os.name").lowercase()
val isWindows = osName.startsWith("windows")
val isLinux = osName.startsWith("linux")
val wasmtimeClassifier = when {
    isWindows -> "windows-x86_64"
    isLinux -> "linux-x86_64"
    else -> error("Unsupported OS: $osName")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":mpv"))
    implementation(compose.desktop.currentOs)
    implementation(compose.materialIconsExtended)
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation("xyz.orbitary:perigee:0.0.2")
    implementation("org.jetbrains.compose.material3:material3-desktop:1.7.3")
    implementation("org.jetbrains.compose.foundation:foundation-desktop:1.7.3")
    implementation("org.jetbrains.compose.ui:ui-desktop:1.7.3")
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("ai.tegmentum:wasmtime4j:48.0.0-2.0.0")
    implementation("ai.tegmentum:wasmtime4j-jni:48.0.0-2.0.0") {
        exclude(group = "ai.tegmentum", module = "wasmtime4j-native")
    }
    implementation("ai.tegmentum:wasmtime4j-native:48.0.0-2.0.0:$wasmtimeClassifier")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

// Overridable from CI: -PappVersion=1.2.3 -Pportable=true
val defaultVersion = "1.0.7"
val appVersion = providers.gradleProperty("appVersion").orNull ?: defaultVersion
// Windows installers only accept x.y.z, so a fork suffix such as "-AK2" stays out of the package version.
// This fork's release names come from fork-version.properties and arrive here as -PappVersion.
val packageSemver = Regex("""^\d+\.\d+\.\d+""").find(appVersion)?.value ?: appVersion
val portableBuild = providers.gradleProperty("portable").orNull == "true"
val generatedVersionResources = layout.buildDirectory.dir("generated/versionResources")

val generateVersionProperties = tasks.register<WriteProperties>("generateVersionProperties") {
    destinationFile.set(generatedVersionResources.get().file("version/version.properties"))
    property("version", appVersion)
}

sourceSets.main {
    resources.srcDir(generatedVersionResources)
}

tasks.processResources {
    dependsOn(generateVersionProperties)
}

compose.desktop {
    application {
        mainClass = "me.enivzoy.anilife.app.MainKt"
        nativeDistributions {
            if (isLinux) {
                targetFormats(TargetFormat.Deb)
            } else {
                targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            }
            packageName = "AniLife"
            packageVersion = packageSemver
            appResourcesRootDir = file("src/main/appResources")
            modules("java.instrument", "java.net.http", "jdk.httpserver", "jdk.unsupported")
            linux {
                packageName = "anilife"
                shortcut = true
                iconFile.set(file("src/main/resources/anilife-256.png"))
            }
            windows {
                menu = !portableBuild
                shortcut = !portableBuild
                perUserInstall = portableBuild
                dirChooser = portableBuild
                iconFile.set(file("src/main/resources/anilife.ico"))
                upgradeUuid = if (portableBuild) {
                    "b2c3d4e5-f6a7-4890-8bcd-ef0123456789"
                } else {
                    "a1b2c3d4-e5f6-4789-9abc-def012345678"
                }
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

