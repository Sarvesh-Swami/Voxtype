import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ONE CODEBASE, TWO BUILDS
//  release: what goes to Google Play. No server of ours, no self-update, no remote logging.
//           Build:  gradlew bundleRelease  ->  app/build/outputs/bundle/release/app-release.aab
//  debug:   the phone test build.        Build:  gradlew assembleDebug  ->  app/build/outputs/apk/debug/app-debug.apk
//
// PRIVATE DEV TOOLS (optional, git-ignored): dev-local/ at the repo root.
//  If dev-local/android/ exists, DEBUG builds add its code and manifest (the sideload Update button +
//  REQUEST_INSTALL_PACKAGES) and turn on BuildConfig.DEV_TOOLS (live logs to the PC). Settings come from
//  dev-local/dev.properties (speakerLogUrl, devVersionCode).
//  Without dev-local/ (fresh clone, CI), debug builds are plain and still compile: src/nodev has a no-op Updater.
//  RELEASE builds never use dev-local/, whether it exists or not.
//
// SIGNING
//  debug  : keystore/speaker-debug.keystore if present (the key the sideloaded app was first signed with,
//           so it updates in place), else the standard Android debug key.
//  release: keystore/speaker-upload.jks + keystore/upload.properties (Play "upload key"; Google re-signs
//           with the app-signing key it holds). keystore/ is git-ignored: back it up.
// Never change applicationId, and always raise versionCode.
val devLocal = rootProject.file("dev-local")
val devAndroid = File(devLocal, "android")
val devTools = devAndroid.isDirectory
val devProps = Properties().apply {
    File(devLocal, "dev.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
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
        versionCode = 32
        versionName = "1.1.0"
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

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("dev") ?: signingConfigs.getByName("debug")
            if (devTools) versionNameSuffix = "-dev"
            buildConfigField("boolean", "DEV_TOOLS", devTools.toString())
            val logUrl = if (devTools) devProps.getProperty("speakerLogUrl").orEmpty().trim() else ""
            buildConfigField("String", "DEFAULT_LOG_URL", "\"$logUrl\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") // never the debug key
            buildConfigField("boolean", "DEV_TOOLS", "false")
            buildConfigField("String", "DEFAULT_LOG_URL", "\"\"")
        }
    }

    sourceSets {
        getByName("debug") {
            if (devTools) {
                java.srcDir(File(devAndroid, "java"))
                File(devAndroid, "AndroidManifest.xml").takeIf { it.exists() }?.let { manifest.srcFile(it) }
            } else {
                java.srcDir("src/nodev/java")
            }
        }
        getByName("release") { java.srcDir("src/nodev/java") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Debug builds may use their own versionCode from dev-local/dev.properties, so phone tests don't need
// a change to this shared file. It must keep going up.
val devVersionCode = devProps.getProperty("devVersionCode")?.trim()?.toIntOrNull()
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        if (devTools && devVersionCode != null) variant.outputs.forEach { it.versionCode.set(devVersionCode) }
    }
}

// No third-party dependencies: plain Android framework + Kotlin stdlib.
