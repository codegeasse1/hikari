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
    versionCode = 173
    versionName = "0.10.2"
        // CI injects the exact commit SHA the APK was built from, so the
        // in-app update checker can compare it against main's HEAD.
        val gitSha = System.getenv("GIT_SHA") ?: "unknown"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        // ---- Processors this app is built for ----
        // armeabi-v7a is the 32-bit arm build (older/cheaper phones) and
        // arm64-v8a is the 64-bit one (every modern phone). x86 and x86_64 are
        // only ever found in Android emulators, so their native libraries —
        // tens of MB of ffmpeg/media binaries — were dead weight in every
        // phone's download, which is what made the single APK so large.
        // ("remove x86 and x86_64, from our apk as its for emulator we dont
        // need it, its just increasing app size")
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
    }

    // ---- One APK per phone, plus a universal one ----
    // With this on, a release build produces `app-armeabi-v7a-release.apk`,
    // `app-arm64-v8a-release.apk` and `app-universal-release.apk` instead of a
    // single file that carries every processor's libraries. A user can then
    // download just their own phone's build (much less data) or the universal
    // one that works anywhere — and because only the two arm ABIs are built at
    // all now, even the universal APK is smaller than it used to be.
    //
    // Every APK keeps the SAME applicationId, signing key and versionCode, so
    // installations of one over another (and the in-app updater) all work.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            // ---- Shrinking: ON ----
            //
            // The release build was shipping every class of every dependency:
            // ~70 MB of dex, most of it code nothing in this APK can reach
            // (unused Compose components, unused Material icons, library
            // internals). `isShrinkResources` needs `isMinifyEnabled`, so the
            // two go together.
            //
            // It is safe HERE because app/proguard-rules.pro starts with
            // `-dontobfuscate` and keeps every namespace that a plugin or an
            // extension links against by name — read that file before changing
            // anything in it, and re-read it before turning either of these
            // back off in a panic: the fix for a broken plugin is a kept
            // namespace there, not a lost 10 MB here.
            isMinifyEnabled = true
            isShrinkResources = true
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
        // Native libraries are stored COMPRESSED in the APK.
        //
        // The platform default since AGP 4.2 is uncompressed (and page-aligned)
        // so the loader can mmap them straight out of the APK — which is faster
        // to start and is what 16 KB-page devices want — but it also means the
        // biggest files in the download are stored at their raw size. Our two
        // biggest are TorrServer's Go binary (~12 MB per ABI) and Conscrypt
        // (~2 MB per ABI); compressing them costs a little install-time
        // extraction and takes a few MB off every APK.
        jniLibs {
            useLegacyPackaging = true
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
        // The jar's MainActivity is CloudStream's own Android screen, and it
        // does not load in this app: a plugin that merely NAMES it (CineStream
        // builds an Intent to it from its settings dialog) dies with
        // `NoClassDefFoundError: Failed resolution of:
        // Lcom/lagradost/cloudstream3/MainActivity;` — on the MAIN thread, which
        // takes the whole process down. Shadow it the same way CloudStreamApp is
        // shadowed: app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt
        // provides a loadable class that hands the user to Hikari's own main
        // screen, and the jar's copy is dropped so the two cannot collide
        // ("Type ... is defined multiple times") during dex merging.
        //
        // MainActivityKt (the file facade — initCloudStream reads it for the
        // app/insecure Requests) is deliberately NOT matched: these patterns
        // require the '$' or the '.class' immediately after "MainActivity".
        exclude("com/lagradost/cloudstream3/MainActivity.class")
        exclude("com/lagradost/cloudstream3/MainActivity\$*.class")
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
    // Two kotlinx.serialization modules nothing in Hikari's own source touches,
    // added because ANIYOMI EXTENSIONS link against them BY NAME:
    //
    //  * kotlinx-serialization-json-okio provides
    //    `kotlinx.serialization.json.okio.OkioStreamsKt`, which the keiyoushi
    //    utils library (`keiyoushi.utils.Json.parseAs`) reaches through
    //    `Json.decodeFromBufferedSource` on every response-backed parse. It is a
    //    SEPARATE artifact from kotlinx-serialization-json, so an extension that
    //    called it died with
    //    `NoClassDefFoundError: kotlinx/serialization/json/okio/OkioStreamsKt`.
    //  * kotlinx-serialization-protobuf provides
    //    `kotlinx.serialization.protobuf.ProtoBuf`, which
    //    `keiyoushi.utils.ProtobufKt` reads at class-initialisation time
    //    (`val protoInstance: ProtoBuf = Injekt.get()`). Without it, merely
    //    loading that file throws.
    //
    // Both are pure Kotlin with no native code, and proguard-rules.pro's
    // `-keep class kotlinx.serialization.**` already keeps them whole. The
    // ProtoBuf singleton itself is registered in HikariApp (see
    // `registerAniyomiSingletons`).
    implementation(libs.kotlinx.serialization.json.okio)
    implementation(libs.kotlinx.serialization.protobuf)
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
    // Mihon/Aniyomi's dependency-injection container. Aniyomi extension APKs
    // don't receive their dependencies as constructor arguments — the extension
    // loader instantiates a source and the source immediately pulls what it
    // needs (`Application`, `Json`, `NetworkHelper`, `JavaScriptEngine`, …) out
    // of the global `Injekt` scope. Hikari has no DI container of its own, so
    // the same container (mihonapp's fork, which rebuilds injekt for modern
    // Kotlin and patches the registrar) is installed and primed in
    // HikariApp.onCreate — see AniyomiExtensionManager.
    implementation("com.github.mihonapp:injekt:91edab2317")
    // RxJava 1 — Aniyomi's `AnimeHttpSource` still carries the deprecated Rx
    // `fetch*` API that `AnimeCatalogueSource`'s default methods delegate to
    // (extlib-14 extensions only implement the suspend methods, so the Rx bridge
    // vendored in RxCoroutineBridge is what actually runs them).
    implementation("io.reactivex:rxjava:1.3.8")
    // Aniyomi's `HttpServer` (a NanoHTTPD local proxy some extensions use for
    // streams that want same-origin requests). Hikari never starts one, but the
    // class still has to LINK — `AnimeHttpSource.createHttpServer()` and the
    // video-resolving paths name it.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // `HttpLoggingInterceptor` — NetworkHelper's OkHttp stack.
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
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
    // (The bundled yt-dlp "universal extractor" — dev.ffmpegkit-maintained's
    // yt-dlp-android, a full CPython 3.13 embedded through Chaquopy — was
    // removed in 0.9.1. It was ~15 MB of the APK, arm64-only, and only ever
    // ran on pages every other engine had already failed on. See CHANGELOG.)
    implementation(libs.cryptography.core)
    implementation(libs.cryptography.provider.optimal)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// ---------------------------------------------------------------------------
// org.json must never be on the runtime classpath.
//
// Android already provides org.json — it lives in
// /apex/com.android.art/javalib/core-libart.jar — and the boot class loader is
// consulted before ours, so a copy we ship is shadowed: the device runs the
// platform's implementation whichever version we bundle.
//
// NiceHttp (com.github.Blatzar:NiceHttp, the client CloudStream plugins link
// against) declares org.json:json at runtime scope, and that is a *modern*
// org.json with APIs the platform does not have. Shipping it is what broke
// 0.9.2. Our own code calls the platform-compatible `JSONObject(s)`; but with
// shrinking on, R8 treated the bundled copy as the definition and inlined its
// constructor body into our classes, which turned that call into a direct
// `new JSONTokener(String, JSONParserConfiguration)` — a constructor that
// exists only in the bundled copy. At runtime the name resolved to the
// platform's JSONTokener, which has no such constructor, so the app died at
// startup with NoSuchMethodError (0.9.2 / build 158, in AppStore.parseProviders).
// Without shrinking the call stayed a call and went to the platform's
// implementation, which is why 0.9.0 was fine.
//
// Excluding it here makes compile time, R8's view and the device agree on one
// org.json: the platform's. Any dependency that declares org.json:json brings
// this crash back with it, so check for it when adding one.
// ---------------------------------------------------------------------------
configurations.configureEach {
    exclude(mapOf("group" to "org.json", "module" to "json"))
}
