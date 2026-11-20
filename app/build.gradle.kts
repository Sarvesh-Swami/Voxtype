import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// TWO FLAVORS
//  play: what goes to Google Play. No server of ours, no self-update, no remote logging.
//        Build:  gradlew bundlePlayRelease   ->  app/build/outputs/bundle/playRelease/app-play-release.aab
//  dev:  sideloaded test build. Streams logs to your PC (ngrok) and has the in-app Update button.
//        Build:  gradlew assembleDevDebug    ->  app/build/outputs/apk/dev/debug/app-dev-debug.apk
//
// SIGNING
//  dev   : keystore/speaker-debug.keystore (the key the sideloaded app was first signed with, so it
//          updates in place). Keep it backed up.
//  play  : keystore/speaker-upload.jks + keystore/upload.properties (Play "upload key"; Google re-signs
//          with the app-signing key it holds). Keep both backed up, outside git.
// Never change applicationId, and always raise versionCode.
val devKeystore = rootProject.file("keystore/speaker-debug.keystore")
val uploadProps = Properties().apply {
    rootProject.file("keystore/upload.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "app.speaker.dictate"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.speaker.dictate"
        minSdk = 26
        targetSdk = 36 // Google Play: new apps and updates must target Android 16 (API 36)
        versionCode = 19
        versionName = "1.0.0"
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (devKeystore.exists()) {
            create("dev") {
                storeFile = devKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        if (uploadProps.getProperty("storeFile") != null) {
            create("upload") {
                storeFile = rootProject.file("keystore/" + uploadProps.getProperty("storeFile"))
                storePassword = uploadProps.getProperty("storePassword")
                keyAlias = uploadProps.getProperty("keyAlias")
                keyPassword = uploadProps.getProperty("keyPassword")
            }
        }
    }

    flavorDimensions += "dist"
    productFlavors {
        create("play") {
            dimension = "dist"
            buildConfigField("boolean", "DEV_TOOLS", "false")
            buildConfigField("String", "DEFAULT_LOG_URL", "\"\"")
        }
        create("dev") {
            dimension = "dist"
            versionNameSuffix = "-dev"
            buildConfigField("boolean", "DEV_TOOLS", "true")
            // Live-log URL built into the dev app (set speakerLogUrl in gradle.properties).
            val logUrl = (project.findProperty("speakerLogUrl") as String?).orEmpty().trim()
            buildConfigField("String", "DEFAULT_LOG_URL", "\"$logUrl\"")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("dev") ?: signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.findByName("dev")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// No third-party dependencies: plain Android framework + Kotlin stdlib.
