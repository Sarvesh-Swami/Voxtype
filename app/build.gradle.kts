import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release build for Google Play:
//   gradlew bundleRelease  ->  app/build/outputs/bundle/release/app-release.aab
// Signed with the Play upload key: keystore/speaker-upload.jks + keystore/upload.properties
// (both git-ignored; keep them backed up). Google re-signs with the app-signing key it holds.
// Never change applicationId, and always raise versionCode for each upload.
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
        versionCode = 18
        versionName = "1.0.0"
    }

    buildFeatures { buildConfig = true } // VERSION_NAME / VERSION_CODE shown in the app

    signingConfigs {
        if (uploadProps.getProperty("storeFile") != null) {
            create("upload") {
                storeFile = rootProject.file("keystore/" + uploadProps.getProperty("storeFile"))
                storePassword = uploadProps.getProperty("storePassword")
                keyAlias = uploadProps.getProperty("keyAlias")
                keyPassword = uploadProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("upload")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// No third-party dependencies: plain Android framework + Kotlin stdlib.
