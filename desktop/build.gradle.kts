import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URL

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

group = "com.hikari"
version = "0.12.166"

java {
    sourceCompatibility = org.gradle.api.JavaVersion.VERSION_17
    targetCompatibility = org.gradle.api.JavaVersion.VERSION_17
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    // JS extension engines (V8) + Windows natives.
    implementation("com.caoccao.javet:javet:4.1.2")
    implementation("com.caoccao.javet:javet-v8-windows-x86_64-i18n:4.1.2")
    // Internal player (JavaFX media) + embedding surface, Windows jars.
    implementation("org.openjfx:javafx-base:21.0.4:win")
    implementation("org.openjfx:javafx-graphics:21.0.4:win")
    implementation("org.openjfx:javafx-controls:21.0.4:win")
    implementation("org.openjfx:javafx-media:21.0.4:win")
    implementation("org.openjfx:javafx-swing:21.0.4:win")
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
            packageVersion = "0.12.166"
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

val hikariJsDir = layout.buildDirectory.dir("generated/hikariJs")

/** JS harness assets ship from the same repo (app/src/main/assets) — downloaded at build time. */
val downloadHikariJs = tasks.register("downloadHikariJs") {
    group = "build"
    description = "Downloads the JS extension-engine harness assets into resources."
    val outDir = hikariJsDir.get().asFile
    outputs.dir(outDir)
    doLast {
        val base = "https://raw.githubusercontent.com/codegeasse1/hikari/main/app/src/main/assets"
        val files = listOf(
            "nuvio/boot.js",
            "nuvio/cheerio.js",
            "nuvio/crypto-js.js",
            "nuvio/harness.js",
            "nuvio/patches/4khdhub.js",
            "nuvio/patches/streamflix.js",
            "nuvio/patches/vornix.js",
            "nuvio/patches/zevran.js",
            "anymex/harness.js",
            "sora/harness.js",
            "skystream/shim.js",
            "vega/commonHeaders.js",
            "vega/harness.js",
        )
        var ok = 0
        for (f in files) {
            val dest = outDir.resolve("hikari-js/$f")
            var attempt = 0
            var saved = false
            while (attempt < 3 && !saved) {
                attempt++
                try {
                    dest.parentFile.mkdirs()
                    URL("$base/$f").openStream().use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (dest.length() > 0) {
                        saved = true
                        ok++
                    }
                } catch (e: Exception) {
                    logger.warn("downloadHikariJs: attempt $attempt for $f failed: ${e.message}")
                    Thread.sleep(2000)
                }
            }
            if (!saved) logger.warn("downloadHikariJs: MISSING $f (engine using it will report it)")
        }
        logger.lifecycle("downloadHikariJs: saved $ok/${files.size} harness assets")
    }
}

sourceSets {
    main {
        resources.srcDir(hikariJsDir)
    }
}

tasks.named("processResources") {
    dependsOn(downloadHikariJs)
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
