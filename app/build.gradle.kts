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
        versionCode = 138
        versionName = "0.5.22"
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
// Strip those before the jar reaches the classpath — the real libraries provide
// their R classes at runtime.
//
// EXCEPT com/lagradost/cloudstream3/R*: the CloudStream jar's OWN R classes.
// Plugins are compiled against them (e.g. syncproviders/AccountManager
// references com.lagradost.cloudstream3.R$string) and NOTHING else on the
// classpath defines that namespace, so stripping them made every class that
// touches them die with
//   NoClassDefFoundError: ...AccountManager
//   caused by ClassNotFoundException: com.lagradost.cloudstream3.R$string
// which surfaced as "No CloudStream plugin loaded" for whole repos (the
// Phisher repo's 85 plugins, StreamPlay, …). They must survive the clean.
val cloudstreamRawJar = file("libs/cloudstream3.jar")
val cloudstreamCleanJar = tasks.register<org.gradle.api.tasks.bundling.Jar>("cloudstreamJarClean") {
    archiveFileName.set("cloudstream3-clean.jar")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/cloudstream-clean"))
    from(zipTree(cloudstreamRawJar)) {
        exclude { element ->
            val path = element.path
            val name = path.substringAfterLast('/')
            val isRClass = name == "R.class" || (name.startsWith("R$") && name.endsWith(".class"))
            val isCloudStreamR = path == "com/lagradost/cloudstream3/R.class" ||
                (path.startsWith("com/lagradost/cloudstream3/R$") && name.endsWith(".class"))
            isRClass && !isCloudStreamR
        }
        // The jar ships the JVM (desktop) artifact of CloudStream, whose
        // network/WebViewResolver is a no-op stub (`intercept` passes through,
        // resolveUsingWebView returns nothing). Plugins that resolve embeds
        // through a real WebView (TamilBlasters' StreamHG/Hgcloud hgcloud.to
        // dance, …) pass a WebViewResolver to app.get(...) and silently got
        // zero servers. Shadow it with a real Android implementation in
        // app/src/main/java/com/lagradost/cloudstream3/network/ — so drop the
        // stub classes here to avoid a duplicate-class build failure.
        exclude("com/lagradost/cloudstream3/network/WebViewResolver*.class")
        // The jar's CloudflareKiller is the Android build of CloudStream's
        // auto Cloudflare solver: its init() WIPES the WebView cookie jar
        // (`CookieManager.removeAllCookies`), and on a 403/503 it loads the
        // challenged site in a WebView all by itself — no user tap. It also
        // calls `WebViewResolver.Companion.getWebViewUserAgent1()`, which the
        // jar's own WebViewResolver stub never declared, so it crashed the app
        // with NoSuchMethodError any time an extension (Cinemacity's
        // Cloudflare-bypass interceptor) wrapped a request with it. Shadow it
        // with app/src/main/java/com/lagradost/cloudstream3/network/
        // CloudflareKiller.kt (same public method table as the jar's class, so
        // plugin bytecode still links) — a version that never opens a WebView,
        // never clears cookies, and simply reuses the clearance the user
        // earned with the app's own verify button.
        exclude("com/lagradost/cloudstream3/network/CloudflareKiller*.class")
        // The jar's CloudStreamApp is compiled against Coil 3 (it implements
        // coil3.SingletonImageLoader.Factory), which this app does NOT bundle
        // (it ships Coil 2) — so any plugin that touches the class dies with
        // NoClassDefFoundError the moment it resolves (seen with Cinemacity's
        // Cloudflare-bypass interceptor). Shadow it with a self-contained
        // host-side implementation in
        // app/src/main/java/com/lagradost/cloudstream3/CloudStreamApp.kt and
        // drop the jar classes to avoid a duplicate-class build failure.
        exclude("com/lagradost/cloudstream3/CloudStreamApp*.class")
        // The jar's ToastBinding is a generated ViewBinding class that
        // (a) cannot be linked without the androidx.viewbinding runtime and
        // (b) inflates by a resource id baked into the jar's own R$layout,
        // which Hikari's resource table does not share. Shadow it with
        // app/src/main/java/com/lagradost/cloudstream3/databinding/ToastBinding.java
        // (same class name + the three members CommonActivity.showToast uses),
        // so plugin toasts render Hikari's own layout instead of dying with
        // NoClassDefFoundError. CommonActivity is the only class in the jar
        // that references it.
        exclude("com/lagradost/cloudstream3/databinding/ToastBinding.class")
    }
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(files(cloudstreamCleanJar))
    // nuvio provider runtime — dokar quickjs-kt (the exact engine the real
    // NuvioMobile app bundles) vendored as an AAR in app/libs/. Runs provider
    // JS in an embedded QuickJS VM instead of a WebView.
    implementation(files("libs/quickjs-kt-android-1.0.5-nuvio.aar"))
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
    // CloudStream plugins ship their own settings UI and many (SKTech's
    // `com.cncverse.Settings`, …) implement it as a
    // com.google.android.material.bottomsheet.BottomSheetDialogFragment. Without
    // the real Material Components library the plugin's `openSettings` callback
    // dies with NoClassDefFoundError the instant the gear is tapped — the click
    // looks like a no-op. cloudstream3.jar only carries Material's R classes
    // (stripped by cloudstreamJarClean), so this is the sole Material runtime.
    implementation("com.google.android.material:material:1.12.0")
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
    // media3-effect — the GPU video-effects pipeline (GlEffect / HslAdjustment /
    // RgbAdjustment / Brightness / Contrast). ExoPlayer.setVideoEffects() is
    // inert without it: the classes are loaded reflectively by the frame
    // processor, so the dependency has to be on the classpath even though no
    // source file names it. Used by the player's "Video enhance" presets.
    implementation(libs.androidx.media3.effect)
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
    // SVG decoding for extension logos: plenty of plugin/addon icons are
    // `.svg` (e.g. SkyStream's dramayo → dramayo.stream/static/dramayo.svg),
    // and Coil 2 answers those with a decode failure — i.e. the monochrome
    // glyph placeholder on every row. Registered in HikariApp's ImageLoader.
    implementation(libs.coil.svg)
    // Animated GIF decoding, for collection/folder covers ("Animated GIF URL"
    // in the cover editor): without it Coil draws only the first frame.
    implementation(libs.coil.gif)
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
    // Required to LINK the CloudStream jar's generated ViewBinding classes
    // (ToastBinding et al.) — they implement androidx.viewbinding.ViewBinding
    // and call ViewBindings.findChildViewById. Without the viewbinding runtime
    // ART fails the class link and surfaces it as
    // NoClassDefFoundError: Lcom/lagradost/cloudstream3/databinding/ToastBinding;
    // which killed the app whenever a plugin called CommonActivity.showToast.
    implementation(libs.androidx.viewbinding)
    // CardView is the declared type of ToastBinding.getRoot() (and the root of
    // res/layout/hikari_toast.xml), so the class must be on the compile+runtime
    // classpath too.
    implementation(libs.androidx.cardview)
    implementation(libs.yt.dlp.android)
    implementation(libs.cryptography.core)
    implementation(libs.cryptography.provider.optimal)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
