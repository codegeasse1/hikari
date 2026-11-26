import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

group = "com.hikari"
version = "0.12.162"

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
            packageVersion = "0.12.162"
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
