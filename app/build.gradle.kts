plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// UPDATES WITHOUT UNINSTALLING: Android only accepts an update signed with the same key as the
// installed app. keystore/speaker-debug.keystore is the exact key v0.1.0 was signed with
// (copied from ~/.android/debug.keystore). Keep it backed up; losing it means a reinstall.
// Also: never change applicationId, and always raise versionCode.
val speakerKeystore = rootProject.file("keystore/speaker-debug.keystore")

android {
    namespace = "app.speaker.dictate"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.speaker.dictate"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.7.0"
        // Live-log URL built into the app (set speakerLogUrl in gradle.properties).
        val logUrl = (project.findProperty("speakerLogUrl") as String?).orEmpty().trim()
        buildConfigField("String", "DEFAULT_LOG_URL", "\"$logUrl\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (speakerKeystore.exists()) {
            create("speaker") {
                storeFile = speakerKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        val signing = signingConfigs.findByName("speaker") ?: signingConfigs.getByName("debug")
        debug { signingConfig = signing }
        release {
            isMinifyEnabled = false
            signingConfig = signing
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// No third-party dependencies: plain Android framework + Kotlin stdlib.
