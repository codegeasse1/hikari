import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hikari.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hikari.app"
        minSdk = 24
       targetSdk = 34
        versionCode = 468
        versionName = "0.12.79"
        // CI injects the exact commit SHA the APK was built from, so the
        // in-app update checker can compare it against main's HEAD.
        val gitSha = System.getenv("GIT_SHA") ?: "unknown"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        // The repository the build runs in (owner/name). The cloned-voice model for Dub audio is
        // kept in the "clone-model" release of that repository (.github/workflows/clone-model.yml).
        val buildRepo = System.getenv("GITHUB_REPOSITORY") ?: ""
        buildConfigField("String", "BUILD_REPO", "\"$buildRepo\"")
        // Processors this app is built for
        // armeabi-v7a is the 32-bit arm build (older/cheaper phones) and
        // arm64-v8a is the 64-bit one (every modern phone). x86 and x86_64 are
        // only ever found in Android emulators, so their native libraries ÃÂ¢ÃÂÃÂ
        // tens of MB of ffmpeg/media binaries ÃÂ¢ÃÂÃÂ were dead weight in every
        // phone's download, which is what made the single APK so large.
        // ("remove x86 and x86_64, from our apk as its for emulator we dont
        // need it, its just increasing app size")
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
    }

    // One APK per phone, plus a universal one
    // With this on, a release build produces `app-armeabi-v7a-release.apk`,
    // `app-arm64-v8a-release.apk` and `app-universal-release.apk` instead of a
    // single file that carries every processor's libraries. A user can then
    // download just their own phone's build (much less data) or the universal
    // one that works anywhere ÃÂ¢ÃÂÃÂ and because only the two arm ABIs are built at
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
            // Shrinking: ON
            //
            // The release build was shipping every class of every dependency:
            // ~70 MB of dex, most of it code nothing in this APK can reach
            // (unused Compose components, unused Material icons, library
            // internals). `isShrinkResources` needs `isMinifyEnabled`, so the
            // two go together.
            //
            // It is safe HERE because app/proguard-rules.pro starts with
            // `-dontobfuscate` and keeps every namespace that a plugin or an
            // extension links against by name ÃÂ¢ÃÂÃÂ read that file before changing
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
            // The vendored hany18h parsers use their own internal API surface
            // freely (their own build opts in); they compile here under the
            // same opt-in rather than annotating hundreds of call sites.
            optIn.add("org.hany18h.parsers.InternalParsersApi")
            // Same story for kotlin.contracts (their validation helpers use
            // contracts, opted in at their own build).
            optIn.add("kotlin.contracts.ExperimentalContracts")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // OkHttp 5 ships Java 9 multi-release jars
            //
            // okhttp-brotli, logging-interceptor and okhttp-dnsoverhttps are
            // published as MULTI-RELEASE jars: each one carries the same
            // JVM-only metadata under `META-INF/versions/9/` ÃÂ¢ÃÂÃÂ a JPMS/OSGi
            // bundle manifest plus the `module-info.class` next to it. AGP's
            // Java-resource merger copies those like any other resource, so the
            // second copy of a path is a hard error and the build stops at
            // `:app:mergeDebugJavaResource`:
            //
            //   3 files found with path 'META-INF/versions/9/OSGI-INF/MANIFEST.MF'
            //     - com.squareup.okhttp3:okhttp-brotli:5.4.0/okhttp-brotli-5.4.0.jar
            //     - com.squareup.okhttp3:logging-interceptor:5.4.0/logging-interceptor-5.4.0.jar
            //     - com.squareup.okhttp3:okhttp-dnsoverhttps:5.3.2/okhttp-dnsoverhttps-5.3.2.jar
            //
            // Both of those entries are JVM metadata that Android cannot use:
            // the manifest describes an OSGi bundle, the class beside it
            // describes a JPMS module, and an APK is neither. They are dead
            // weight, so they are dropped instead of being picked between.
            // (Verified by listing the three real jars: those two entries are
            // everything any of them puts under `versions/9`.)
            excludes += "META-INF/versions/**/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/versions/**/module-info.class"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/versions/9/module-info.class"
        }
        // Native libraries are stored COMPRESSED in the APK.
        //
        // The platform default since AGP 4.2 is uncompressed (and page-aligned)
        // so the loader can mmap them straight out of the APK ÃÂ¢ÃÂÃÂ which is faster
        // to start and is what 16 KB-page devices want ÃÂ¢ÃÂÃÂ but it also means the
        // biggest files in the download are stored at their raw size. Our two
        // biggest are TorrServer's Go binary (~12 MB per ABI) and Conscrypt
        // (~2 MB per ABI); compressing them costs a little install-time
        // extraction and takes a few MB off every APK.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

val tdJniStageDir = layout.buildDirectory.dir("td-jni").get().asFile.apply { mkdirs() }
val stageTdJniLibs = tasks.register<Sync>("stageTdJniLibs") {
    from(layout.projectDirectory.dir("src/main/jniLibs")) {
        include("**/libcxx_shared.so")
    }
    into(tdJniStageDir)
    rename { name -> if (name == "libcxx_shared.so") "libc++_shared.so" else name }
}
android.sourceSets.getByName("main").jniLibs.srcDir(tdJniStageDir)
tasks.matching {
    it.name.contains("JniLibFolders") || it.name.contains("NativeLibs") ||
        it.name.contains("NativeDebugMetadata")
}.configureEach { dependsOn(stageTdJniLibs) }

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
        exclude("com/lagradost/cloudstream3/network/WebViewResolver*.class")
        exclude("com/lagradost/cloudstream3/network/CloudflareKiller*.class")
        exclude("com/lagradost/cloudstream3/CloudStreamApp*.class")
        // The jar's MainActivity is CloudStream's own Android screen, and it
        // does not load in this app: a plugin that merely NAMES it (CineStream
        // builds an Intent to it from its settings dialog) dies with
        // `NoClassDefFoundError: Failed resolution of:
        // Lcom/lagradost/cloudstream3/MainActivity;` ÃÂ¢ÃÂÃÂ on the MAIN thread, which
        // takes the whole process down. Shadow it the same way CloudStreamApp is
        // shadowed: app/src/main/java/com/lagradost/cloudstream3/MainActivity.kt
        // provides a loadable class that hands the user to Hikari's own main
        // screen, and the jar's copy is dropped so the two cannot collide
        // ("Type ... is defined multiple times") during dex merging.
        //
        // MainActivityKt (the file facade ÃÂ¢ÃÂÃÂ initCloudStream reads it for the
        // app/insecure Requests) is deliberately NOT matched: these patterns
        // require the '$' or the '.class' immediately after "MainActivity".
        exclude("com/lagradost/cloudstream3/MainActivity.class")
        exclude("com/lagradost/cloudstream3/MainActivity\$*.class")
        exclude("com/lagradost/cloudstream3/databinding/ToastBinding.class")
    }
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(files(cloudstreamCleanJar))
    implementation(files("libs/quickjs-kt-android-1.0.5-nuvio.aar"))
    // Sherpa-ONNX - offline neural TTS (Piper voices) for AI Dub. The AAR is
    // fetched from the official sherpa-onnx release in CI (see build.yml);
    // run that same download for a local build.
    implementation(files("libs/sherpa-onnx.aar"))
    // Piper voices run on sherpa-onnx 1.13.8 (espeak-ng built into the
    // AAR, dataDir from the voice pack) - no piper-phonemize needed.
    // tar.bz2 voice packs from the official sherpa tts-models release.
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation("com.github.recloudstream:torrentserver:7861970")
    implementation(libs.androidx.core.ktx)
    // CloudStream plugins cast `app` to androidx.appcompat.app.AppCompatActivity
    // (MovieBox/Phisher repo do it in load()); the app must ship the real
    // AppCompat classes AND MainActivity must be an AppCompatActivity for that
    // cast to succeed.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
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
    implementation(libs.androidx.media3.effect)
    implementation("io.github.anilbeesetti:nextlib-media3ext:1.7.1-0.9.0")
    implementation(libs.androidx.splashscreen)
    implementation(libs.okhttp)
    implementation(libs.okhttp.brotli)
    implementation(libs.okhttp.zstd)
    implementation(libs.zstd.kmp.okio)
    implementation(libs.jsoup)
    implementation("org.jspecify:jspecify:1.0.0")
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    // Animated GIF decoding, for collection/folder covers ("Animated GIF URL"
    // in the cover editor): without it Coil draws only the first frame.
    implementation(libs.coil.gif)
    // The manga reader (Nekoread's reader, ported whole)
    //
    // Three libraries the ported viewer stack is built on, all of them the exact
    // artifacts Nekoread itself uses (a JitPack commit for the Tachiyomi fork of
    // SubsamplingScaleImageView ÃÂ¢ÃÂÃÂ the view that region-decodes a long strip from
    // its cache file instead of holding a page-sized bitmap in memory ÃÂ¢ÃÂÃÂ and the
    // Tachiyomi fork of DirectionalViewPager, which is the ONE view that can page
    // vertically as well as horizontally, i.e. the "paged vertical" reading mode).
    //
    // recyclerview is explicit rather than assumed: the webtoon viewer IS a
    // RecyclerView (with Nekoread's own WebtoonLayoutManager, which subclasses
    // LinearLayoutManager), and a reader that only compiled because a Compose
    // dependency happened to bring RecyclerView along would break the day it did
    // not.
    implementation("com.github.tachiyomiorg:subsampling-scale-image-view:66e0db195d")
    implementation("com.github.tachiyomiorg:DirectionalViewPager:1.0.0") {
        // DirectionalViewPager re-exports an older androidx.viewpager; keep only
        // the version pinned below, or the two copies fight over DuplicateClass.
        exclude(group = "androidx.viewpager", module = "viewpager")
    }
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.viewpager:viewpager:1.1.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation(libs.kotlinx.serialization.json)
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
    implementation("com.github.mihonapp:injekt:91edab2317")
    implementation("io.reactivex:rxjava:1.3.8")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation(libs.logging.interceptor)
    // Required to LINK the CloudStream jar's generated ViewBinding classes
    // (ToastBinding et al.) ÃÂ¢ÃÂÃÂ they implement androidx.viewbinding.ViewBinding
    // and call ViewBindings.findChildViewById. Without the viewbinding runtime
    // ART fails the class link and surfaces it as
    // NoClassDefFoundError: Lcom/lagradost/cloudstream3/databinding/ToastBinding;
    // which killed the app whenever a plugin called CommonActivity.showToast.
    implementation(libs.androidx.viewbinding)
    implementation(libs.androidx.cardview)
    implementation(libs.cryptography.core)
    implementation(libs.cryptography.provider.optimal)
    implementation("com.github.KotatsuApp:kotatsu-parsers:4d1e521aef") {
        exclude(group = "org.json", module = "json")
    }
    // Kototoro parsers ÃÂ¢ÃÂÃÂ skepsun's independent Kotatsu fork (manga/manhwa /
    // novels PLUS video sources: anime clips, hentai video). A separate
    // package (org.skepsun.kototoro.parsers) with zero shared classes, so it
    // links beside the official library with no conflicts. Pinned to the
    // verified master SHA (same artifact the batch-105 verification built via
    // JitPack). org.json excluded like above.
    implementation("com.github.skepsun:kototoro-parsers:c416529309d3b26d0ec0afca90f00290500d4b82") {
        exclude(group = "org.json", module = "json")
    }
    // Device pairing (Settings ÃÂ¢ÃÂÃÂ Backup & Restore ÃÂ¢ÃÂÃÂ Pair & sync)
    //
    // The feature itself is a few hundred lines: two `PairHost`/`PairClient`
    // objects and a screen. Its two dependencies are what turn "the code on one
    // screen" into "the code on one screen that the other device can read":
    //
    //  * zxing-core ÃÂ¢ÃÂÃÂ the QR encoder AND decoder, pure Java, no Android
    //    dependencies, no camera permission, ~500 KB. Encoding a pairing payload
    //    and decoding a camera frame are both one call into it
    //    (see com.hikari.app.pair.QrCode). It is the same library every Android
    //    scanner app is built on; the alternative (a barcode API that only
    //    exists on devices with Play Services) is exactly the wrong trade for an
    //    app whose whole point is that it runs on a cheap Chinese television.
    //  * CameraX ÃÂ¢ÃÂÃÂ the scanner's camera. camera-view brings `PreviewView` (the
    //    surface that actually draws the preview and does the device's own
    //    rotation/scale handling, which doing it by hand is a week of bugs), and
    //    camera-camera2 the Camera2CameraImpl underneath it. The OTHER camera in
    //    this app is the system WebView's, which is none of our business.
    //
    // Both are only ever used by the pairing screen: a device that never opens it
    // never starts a camera or a decoder.
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // The in-app lock (Settings ÃÂ¢ÃÂÃÂ Privacy & Browsing ÃÂ¢ÃÂÃÂ App lock)
    //
    //  * biometric ÃÂ¢ÃÂÃÂ the fingerprint/face prompt itself. The androidx one, not
    //    the platform's: this app runs from API 24 and the platform
    //    BiometricPrompt only exists from API 28, so the platform API would mean
    //    writing (and testing) two different prompts on two different paths.
    //  * lifecycle-process ÃÂ¢ÃÂÃÂ ProcessLifecycleOwner, the ONE signal that means
    //    "the app went to the background". Locking on the activity's own stop
    //    would re-lock behind the player (a separate activity), i.e. a password
    //    prompt every time a film ends.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.process)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

//
// org.json must never be on the runtime classpath.
//
// Android already provides org.json ÃÂ¢ÃÂÃÂ it lives in
// /apex/com.android.art/javalib/core-libart.jar ÃÂ¢ÃÂÃÂ and the boot class loader is
// consulted before ours, so a copy we ship is shadowed: the device runs the
// platform's implementation whichever version we bundle.
//
// NiceHttp (com.github.Blatzar:NiceHttp, the client CloudStream plugins link
// against) declares org.json:json at runtime scope, and that is a *modern*
// org.json with APIs the platform does not have. Shipping it is what broke
// 0.9.2. Our own code calls the platform-compatible `JSONObject(s)`; but with
// shrinking on, R8 treated the bundled copy as the definition and inlined its
// constructor body into our classes, which turned that call into a direct
// `new JSONTokener(String, JSONParserConfiguration)` ÃÂ¢ÃÂÃÂ a constructor that
// exists only in the bundled copy. At runtime the name resolved to the
// platform's JSONTokener, which has no such constructor, so the app died at
// startup with NoSuchMethodError (0.9.2 / build 158, in AppStore.parseProviders).
// Without shrinking the call stayed a call and went to the platform's
// implementation, which is why 0.9.0 was fine.
//
// Excluding it here makes compile time, R8's view and the device agree on one
// org.json: the platform's. Any dependency that declares org.json:json brings
// this crash back with it, so check for it when adding one.
//
configurations.configureEach {
    exclude(mapOf("group" to "org.json", "module" to "json"))
}

configurations.configureEach {
    resolutionStrategy.force("com.squareup.okhttp3:okhttp-dnsoverhttps:${libs.versions.okhttp.get()}")
}
