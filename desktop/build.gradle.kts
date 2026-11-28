import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

group = "com.hikari"
version = "0.12.165"

java {
    sourceCompatibility = org.gradle.api.JavaVersion.VERSION_17
    targetCompatibility = org.gradle.api.JavaVersion.VERSION_17
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnit()
}

compose.desktop {
    application {
        mainClass = "com.hikari.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Hikari"
            packageVersion = "0.12.165"
            description = "Hikari desktop media browser"
            vendor = "Codegeasse"
            modules("java.desktop", "java.net.http", "java.prefs")

            windows {
                perUserInstall = true
                menuGroup = "Hikari"
                upgradeUuid = "d6f41c09-2b4d-4d11-9228-839f1de3f64e"
            }
        }
    }
}

val stripAppLocalUcrt = tasks.register("stripAppLocalUcrt") {
    group = "distribution"
    description = "Use the Windows system UCRT instead of packaging app-local copies."
    dependsOn("createRuntimeImage")
    outputs.upToDateWhen { false }

    doLast {
        val composeBuildDir = layout.buildDirectory.dir("compose").get().asFile
        val ucrtFiles = composeBuildDir.walkTopDown().filter { file ->
            val parentName = file.parentFile?.name?.lowercase()
            val runtimeName = file.parentFile?.parentFile?.name?.lowercase()
            file.isFile &&
                parentName == "bin" &&
                runtimeName == "runtime" &&
                (file.name.equals("ucrtbase.dll", ignoreCase = true) ||
                    (file.name.startsWith("api-ms-win-crt-", ignoreCase = true) &&
                        file.extension.equals("dll", ignoreCase = true)))
        }.toList()

        ucrtFiles.forEach { file ->
            if (!file.delete()) {
                throw org.gradle.api.GradleException("Could not remove app-local UCRT file: ${file.path}")
            }
        }
        logger.lifecycle("Removed ${ucrtFiles.size} app-local UCRT DLL(s) from the Windows runtime image.")
    }
}

tasks.matching { it.name == "packageMsi" || it.name == "packageExe" }.configureEach {
    dependsOn(stripAppLocalUcrt)
}
