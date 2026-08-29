import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hikari.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hikari.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 62
        versionName = "0.3.59"
        // CI injects the exact commit SHA the APK was built from, so the
        // in-app update checker can compare it against main's HEAD.
        val gitSha = System.getenv("GIT_SHA") ?: "unknown"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // CI passes signing config via env vars (decoded from the SIGNING_KEY
            // repo secret). Local builds stay unsigned.
            val storePath = System.getenv("SIGNING_STORE_PATH")
            if (!storePath.isNullOrBlank()) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(storePath)
                    storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                    keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                    keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// cloudstream3.jar is a precompiled library that ships compiled R classes for
// every namespace it touches (androidx/activity/compose/R.class,
// com.fleeksoft.charset.R, ...). Those collide with the real dependencies' R
// classes during release dex merging ("Type ...R is defined multiple times").
// Strip ALL R classes before the jar reaches the classpath — a bare jar has no
// resource table, so its R classes are dead scaffolding; the real libraries
// provide the R classes at runtime.
val cloudstreamRawJar = file("libs/cloudstream3.jar")
val cloudstreamCleanJar = tasks.register<org.gradle.api.tasks.bundling.Jar>("cloudstreamJarClean") {
    archiveFileName.set("cloudstream3-clean.jar")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/cloudstream-clean"))
    from(zipTree(cloudstreamRawJar)) {
        exclude("**/R.class", "**/R$*.class")
        // The jar ships the JVM (desktop) artifact of CloudStream, whose
        // network/WebViewResolver is a no-op stub (`intercept` passes through,
        // resolveUsingWebView returns nothing). Plugins that resolve embeds
        // through a real WebView (TamilBlasters' StreamHG/Hgcloud hgcloud.to
        // dance, …) pass a WebViewResolver to app.get(...) and silently got
        // zero servers. Shadow it with a real Android implementation in
        // app/src/main/java/com/lagradost/cloudstream3/network/ — so drop the
        // stub classes here to avoid a duplicate-class build failure.
        exclude("com/lagradost/cloudstream3/network/WebViewResolver*.class")
        // The jar's CloudStreamApp is compiled against Coil 3 (it implements
        // coil3.SingletonImageLoader.Factory), which this app does NOT bundle
        // (it ships Coil 2) — so any plugin that touches the class dies with
        // NoClassDefFoundError the moment it resolves (seen with Cinemacity's
        // Cloudflare-bypass interceptor). Shadow it with a self-contained
        // host-side implementation in
        // app/src/main/java/com/lagradost/cloudstream3/CloudStreamApp.kt and
        // drop the jar classes to avoid a duplicate-class build failure.
        exclude("com/lagradost/cloudstream3/CloudStreamApp*.class")
    }
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(files(cloudstreamCleanJar))
    // TorrServer — the Go torrent engine CloudStream's own Torrent object uses.
    // Lets Stremio torrent addons (Torrentio, Comet, MediaFusion…) actually play:
    // magnet/infoHash streams become HLS served from a local TorrServer process.
    implementation("com.github.recloudstream:torrentserver:7861970")
    implementation(libs.androidx.core.ktx)
    // CloudStream plugins cast `app` to androidx.appcompat.app.AppCompatActivity
    // (MovieBox/Phisher repo do it in load()); the app must ship the real
    // AppCompat classes AND MainActivity must be an AppCompatActivity for that
    // cast to succeed.
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Phisher/Kotlin plugins (Anikoto, …) are compiled against Gson and call it
    // at runtime (e.g. AnikotoExtractors.extractMegaPlayUrl) — without it they
    // die with NoClassDefFoundError: com.google.gson.JsonObject.
    implementation("com.google.code.gson:gson:2.11.0")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.ui)
    // nextlib-media3ext — prebuilt FFmpeg software decoders for Media3. Many
    // provider streams (e.g. 4kHDHub MKVs) carry EAC-3/AC-3/DTS/TrueHD audio
    // that the device's MediaCodec can't decode, so the video plays silently.
    // NextRenderersFactory adds FfmpegAudioRenderer/FfmpegVideoRenderer that
    // kick in (MODE_ON) whenever the hardware codecs can't handle a track.
    // Built against media3 1.7.1 (see nextlib-media3ext POM) — keep the media3
    // version in libs.versions.toml matched to it.
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.7.1-0.9.0")
    implementation(libs.androidx.splashscreen)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.nicehttp)
    implementation(libs.conscrypt.android)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.rhino)
    implementation(libs.ktor.http)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ksoup)
    implementation(libs.kotlinx.datetime)
    implementation(libs.atomicfu)
    implementation(libs.newpipeextractor)
    implementation(libs.yt.dlp.android)
    implementation(libs.cryptography.core)
    implementation(libs.cryptography.provider.optimal)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
