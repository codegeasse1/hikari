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
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// cloudstream3.jar is a precompiled library that ships compiled androidx R
// classes (androidx/activity/compose/R.class, androidx/activity/R.class, ...).
// Those collide with the real AAR R classes during release dex merging
// ("Type androidx.activity.compose.R is defined multiple times"). Strip them
// before the jar reaches the classpath — the real AARs provide the R classes.
val cloudstreamRawJar = file("libs/cloudstream3.jar")
val cloudstreamCleanJar = tasks.register<org.gradle.api.tasks.bundling.Jar>("cloudstreamJarClean") {
    archiveFileName.set("cloudstream3-clean.jar")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/cloudstream-clean"))
    from(zipTree(cloudstreamRawJar)) {
        exclude("androidx/**/R.class", "androidx/**/R\$*.class")
    }
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(files(cloudstreamCleanJar)) { builtBy(cloudstreamCleanJar) }
    implementation(libs.androidx.core.ktx)
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
    implementation(libs.cryptography.core)
    implementation(libs.cryptography.provider.optimal)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
