# ---------------------------------------------------------------------------
# Hikari — release shrinking rules.
#
# Read this before changing anything in here.
#
# Hikari is a HOST: its job is to load code that the user installs later.
#
#   - CloudStream plugins  (plugin APKs / `.jar`s, compiled against the
#     `com.lagradost.cloudstream3.*` API shipped in app/libs/cloudstream3.jar)
#   - Aniyomi / Mihon extensions (`*.ext` APKs, compiled against the
#     `eu.kanade.tachiyomi.*` API vendored in this module's own source tree)
#   - Hikari's own `.hiki` plugins (compiled against `com.hikari.ext.*`)
#   - Nuvio / SkyStream providers, which run JavaScript in an embedded engine
#     and call back into Java by NAME
#
# None of that code is in this APK. It arrives later, and every reference it
# makes to a class we ship is a *string* inside somebody else's bytecode — a
# reference R8 cannot see. So R8 cannot know which of our classes they need.
# That gives the two rules this whole file is built on:
#
#   1. `-dontobfuscate` — nothing is ever renamed. A name we change is a name
#      their bytecode still asks for, which is the classic way a plugin host
#      breaks on its own release build. (Aniyomi — the most widely used
#      extension host there is — ships exactly this combination: shrink, but
#      never obfuscate.)
#   2. Every API a plugin or an extension can touch is kept WHOLE (`{ *; }`),
#      not just its public members: external bytecode calls members we never
#      call ourselves, and those are precisely the members R8 would drop.
#
# What is still shrunk, and where the saving comes from: everything nothing
# outside this APK can reach — Compose and the rest of our own UI stack,
# media3's unused internals, our own unreachable code — plus unused resources.
#
# If shrinking ever breaks a plugin or an extension, the fix is a *kept
# namespace* below, not a flag: add the package the plugin is complaining
# about, and re-test. And if it ever has to be turned off entirely, it is two
# lines in app/build.gradle.kts (isMinifyEnabled / isShrinkResources).
# ---------------------------------------------------------------------------

# Never rename anything (see above).
-dontobfuscate

# Reflection, generics and annotations are load-bearing here: Jackson and Gson
# read field names and annotations, Injekt resolves types from generic
# signatures, kotlinx.serialization looks for generated serializers, and
# Compose reads its own annotations. Stripping these attributes breaks all of
# them, usually at runtime, in one plugin, on one device.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,MethodParameters,Exceptions

# Keep line numbers: Hikari's own crash logger writes stack traces to a file
# the user can send in, and a trace without them is guesswork.
-keepattributes SourceFile,LineNumberTable

# ---------------------------------------------------------------------------
# 1. CloudStream plugin API (app/libs/cloudstream3.jar)
#
# Plugins are compiled against this jar and call into it by name — including
# `Class.forName("com.lagradost.cloudstream3.MainAPI")` and
# `...MainActivityKt`, which HikariApp and MainActivity resolve as strings.
# ---------------------------------------------------------------------------
-keep class com.lagradost.** { *; }
-dontwarn com.lagradost.**

# ---------------------------------------------------------------------------
# 2. Aniyomi / Mihon extension API (vendored in this module's source tree)
#
# `eu.kanade.tachiyomi.animesource.model.*` and `eu.kanade.tachiyomi.source.*`
# are shipped as source here because that is what an `.ext` APK links against.
# Extensions are loaded through a PathClassLoader and instantiated by name
# (`Class.forName(className, false, loader)`), and Injekt resolves their
# dependencies by walking the call stack — so these names, and these members,
# have to survive exactly as written.
# ---------------------------------------------------------------------------
-keep class eu.kanade.** { *; }
-keep class tachiyomi.** { *; }
-keep class mihon.** { *; }
-keep class dev.mihon.** { *; }
-dontwarn eu.kanade.**
-dontwarn tachiyomi.**
-dontwarn dev.mihon.**

# The one DI container an extension can ask for (`Injekt.get<T>()`).
-keep class uy.kohesive.injekt.** { *; }
-dontwarn uy.kohesive.injekt.**

# The JavaScript engine extensions link against by name (Aniyomi bundles
# `app.cash.quickjs`; Hikari bridges it to the `com.dokar.quickjs` runtime it
# already ships — see app/cash/quickjs/QuickJs.kt). Its members are called
# directly from extension bytecode, so no renaming and no shrinking.
-keep class app.cash.** { *; }
-dontwarn app.cash.**
-keep class com.dokar.quickjs.** { *; }
-dontwarn com.dokar.quickjs.**

# ---------------------------------------------------------------------------
# 3. Hikari's own plugin API
#
# `com.hikari.ext.*` is what a `.hiki` plugin is compiled against, and a
# built-in provider is instantiated BY NAME (HikariRuntime: Class.forName of
# the class name stored in the provider config).
# ---------------------------------------------------------------------------
-keep class com.hikari.ext.** { *; }

# ---------------------------------------------------------------------------
# 3b. androidx.biometric (the in-app lock's fingerprint prompt)
#
# The prompt is a fragment `androidx.biometric.BiometricFragment` that the
# library instantiates FROM A STRING ("androidx.biometric.BiometricFragment")
# and attaches to the host activity's FragmentManager, so R8 cannot see the
# reference and would remove the class — the lock would then stop offering the
# fingerprint in the release build only, which is the worst possible place for
# that bug to live. `-dontobfuscate` is already on above, so keeping the classes
# is all that is needed.
# ---------------------------------------------------------------------------
-keep class androidx.biometric.** { *; }
-dontwarn androidx.biometric.**

# ---------------------------------------------------------------------------
# 3c. TDLib (the Telegram client's bindings)
#
# The native library finds the Java side by NAME, not by reference: libtdjni.so
# registers its natives against `org.drinkless.tdlib.Client` in JNI_OnLoad, and
# every object TDLib hands back (`TdApi.UpdateNewMessage`, …) is constructed by
# looking the class up from its own type name and matching a constructor by
# parameter types. R8 sees almost none of that — Client's native methods have no
# callers it can follow, and TdApi's ~2000 classes are only ever named as
# strings — so without these two lines the release build would sign in, receive
# nothing, and report an internal error for every update. The package is ~5 MB
# of generated Java, and keeping it whole is the price of the client working.
# ---------------------------------------------------------------------------
-keep class org.drinkless.tdlib.** { *; }
-dontwarn org.drinkless.tdlib.**

# ---------------------------------------------------------------------------
# 4. Libraries third-party code links against by name
#
# Not one of these is referenced by Hikari's own source — they are here so
# that plugin/extension bytecode finds them at runtime. That is also why each
# one has to be kept whole: R8 sees no reference at all, so left alone it
# would happily delete the lot.
# ---------------------------------------------------------------------------
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.jsoup.** { *; }
# The zstd codec OkHttp 5's `okhttp3.zstd.Zstd` / `okhttp3.CompressionInterceptor`
# drive — and, again, one that only extension bytecode calls: the newer
# keiyoushi multisrc families name `com.squareup.zstd.okio.OkioZstd` directly
# (it is what applies the zstd filter to their on-disk response cache), so
# without this line their sources would compile against it, ship, and then die
# with a NoClassDefFoundError in the release build only. `com.squareup.zstd` is
# the codec behind that adapter, reached through JNI, so it has to stay whole.
-keep class com.squareup.zstd.** { *; }
-dontwarn com.squareup.zstd.**
-dontwarn org.brotli.**
-keep class rx.** { *; }
-keep class io.reactivex.** { *; }
# NanoHTTPD is published under two package names, and the two extension hosts
# we support do not agree on which. `fi.iki.elonen.**` is the original one, and
# it is the one the Aniyomi extension API vendored into this module actually
# extends: `eu/kanade/tachiyomi/animesource/model/HttpServer.kt` implements
# `fi.iki.elonen.NanoHTTPD`, and an extension's `createHttpServer()` hands that
# socket to the reader. `org.nanohttpd.**` is the later fork, kept for
# extensions built against it. Neither is referenced by Hikari's own source, so
# these two lines are the only thing standing between an extension that serves
# its own pages and a NoClassDefFoundError.
-keep class fi.iki.elonen.** { *; }
-keep class org.nanohttpd.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class com.google.gson.** { *; }
-keep class io.ktor.** { *; }
-keep class com.fleeksoft.** { *; }
# (NiceHttp — the HTTP client CloudStream extensions use — is
# `com.lagradost.nicehttp.**`, and the CloudStream rule above already keeps it
# whole. There is no `com.github.blatzar.**` in this app.)
-keep class org.conscrypt.** { *; }
-keep class io.github.anilbeesetti.** { *; }
-keep class coil.** { *; }
-keep class io.coil-kt.** { *; }
-keep class org.schabi.newpipe.** { *; }

# ---------------------------------------------------------------------------
# 5. The AndroidX / Material surface a plugin's own settings screen and dialogs
#    are built from. A CloudStream plugin is a small app: it extends
#    AppCompatActivity, inflates AppCompat/RecyclerView/Material views, uses
#    PreferenceFragmentCompat for its settings sheet and casts the host
#    activity to AppCompatActivity to get a Context.
#
#    Deliberately NOT kept: androidx.compose.**, androidx.navigation.**,
#    androidx.datastore.** and the other libraries only Hikari's own UI uses —
#    that is where the shrinking actually happens. Nothing outside this APK
#    can see Compose.
# ---------------------------------------------------------------------------
-keep class androidx.appcompat.** { *; }
-keep class androidx.fragment.** { *; }
-keep class androidx.recyclerview.** { *; }
-keep class androidx.preference.** { *; }
-keep class androidx.cardview.** { *; }
-keep class androidx.viewbinding.** { *; }
-keep class androidx.annotation.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.webkit.** { *; }
-keep class androidx.media3.** { *; }
-keep class com.google.android.material.** { *; }

# ---------------------------------------------------------------------------
# 6. Reflection anchors
# ---------------------------------------------------------------------------

# XML-inflated views: a plugin's layout may name one of our types, and
# <view class="..."> is a string Android resolves at inflate time.
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# The Nuvio / SkyStream JavaScript bridges: the WebView calls these by name.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Enums read back from a stored string (settings, downloads, history) use
# valueOf()/values() reflectively.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Saved state and download records.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# kotlinx.serialization: the serializer for a class is looked up through a
# generated companion, so the annotation on a class is what keeps it alive.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keep class <1> { *; }

# ---------------------------------------------------------------------------
# 7. Warnings
#
# The libraries above are full of optional integrations (slf4j, Apache
# Commons, javax.script, java.awt, GraalVM, test frameworks, …) that are not
# on the classpath and are never called on Android. R8 treats a missing class
# in *reachable* code as a build failure, and keeping whole namespaces makes a
# lot of that code reachable, so the noise is suppressed by package rather
# than case by case.
# ---------------------------------------------------------------------------
-dontwarn org.slf4j.**
-dontwarn org.apache.**
-dontwarn org.codehaus.**
-dontwarn javax.**
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn sun.misc.**
-dontwarn org.w3c.**
-dontwarn org.xml.**
-dontwarn org.ietf.**
-dontwarn org.jetbrains.**
-dontwarn com.sun.**
-dontwarn com.oracle.**
-dontwarn com.google.errorprone.**
-dontwarn com.google.j2objc.**
-dontwarn com.google.common.**
-dontwarn com.google.protobuf.**
-dontwarn org.checkerframework.**
-dontwarn org.bouncycastle.**
-dontwarn org.gnu.**
-dontwarn org.graalvm.**
-dontwarn org.joda.**
-dontwarn org.hamcrest.**
-dontwarn org.junit.**
-dontwarn org.mockito.**
-dontwarn org.objenesis.**
-dontwarn org.robolectric.**
-dontwarn net.bytebuddy.**
-dontwarn android.test.**
-dontwarn com.fasterxml.jackson.**
-dontwarn com.google.gson.**
-dontwarn io.ktor.**
-dontwarn io.reactivex.**
-dontwarn rx.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.mozilla.javascript.**
# org.json is the platform's — see the note above `configurations.configureEach`
# in build.gradle.kts, which keeps the org.json:json that NiceHttp declares off
# our classpath entirely. That exclusion is the fix for the 0.9.2 startup crash:
# R8 took the shadowed copy as the definition of `JSONObject` and inlined its
# `JSONObject(String)` body into our classes, producing a
# `new JSONTokener(String, JSONParserConfiguration)` call that only the copy
# has. This line is only here for the compile warnings the exclusion leaves
# behind (methods a prebuilt jar calls that the platform's org.json lacks).
#
# A copy can only come back with a dependency that declares org.json:json, and
# if it does, the crash comes back with it — a bundled org.json can never be
# the copy the device runs. Check for it when adding a dependency.
-dontwarn org.json.**
-dontwarn org.jsoup.**
-dontwarn org.conscrypt.**
-dontwarn com.fleeksoft.**
-dontwarn fi.iki.elonen.**
-dontwarn uy.kohesive.injekt.**
-dontwarn eu.kanade.**
-dontwarn tachiyomi.**
-dontwarn dev.mihon.**
-dontwarn com.lagradost.**
-dontwarn org.nanohttpd.**
-dontwarn io.github.anilbeesetti.**
-dontwarn io.coil-kt.**
-dontwarn coil.**
-dontwarn org.schabi.newpipe.**
-dontwarn kotlin.**
-dontwarn kotlinx.**

## ---- RxJava 1.x (Aniyomi's `fetch*` bridge) --------------------------------
-keepclassmembers class rx.internal.util.unsafe.*ArrayQueue*Field* {
   long producerIndex;
   long consumerIndex;
}
-keepclassmembers class rx.internal.util.unsafe.BaseLinkedQueueProducerNodeRef {
    rx.internal.util.atomic.LinkedQueueNode producerNode;
}
-keepclassmembers class rx.internal.util.unsafe.BaseLinkedQueueConsumerNodeRef {
    rx.internal.util.atomic.LinkedQueueNode consumerNode;
}
-dontnote rx.internal.util.PlatformDependent

## ---- OkHttp ---------------------------------------------------------------
-keepclasseswithmembers class okhttp3.MultipartBody$Builder { *; }

## ---- kotlinx.serialization-json ------------------------------------------
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontnote kotlinx.serialization.**
