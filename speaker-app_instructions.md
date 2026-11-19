# speaker-app: agent instructions

Android dictation app ("Speaker", package `app.speaker.dictate`). User-facing overview: `README.md`.
Play Store release steps and policy notes: `docs/play-store-release-checklist.md`.

## Branches
- **dev** (this branch): day-to-day work and device testing. Has the ngrok log server, the in-app Update
  button, the sideload signing key setup and both build flavors below.
- **Main**: the Google Play version only. It has no flavors, no `tools/`, no `dist/`, no updater and no remote
  logging; its README and docs are written for end users. Bring finished work over by merging `dev` into
  `Main`. Git remembers the cleanup commit, so only new dev-only changes need resolving. When files conflict,
  keep Main's versions of `README.md`, this file, `app/build.gradle.kts`, `gradle.properties`, `.gitignore`
  and `RemoteLog.kt`. Rebuild with `gradlew bundleRelease` and check the release has no `ngrok`/`PackageInstaller`.
- Commit locally only; the user pushes from their own account.

## Two flavors (app/build.gradle.kts)
- **play**: the Google Play build. No server of ours, no self-update, no remote logging, no
  REQUEST_INSTALL_PACKAGES. `BuildConfig.DEV_TOOLS = false`. Build: `gradlew bundlePlayRelease`
  → `app/build/outputs/bundle/playRelease/app-play-release.aab` (copy to `dist/play/`, with `mapping.txt`).
  Signed with the upload key `keystore/speaker-upload.jks` + `keystore/upload.properties` (git-ignored; back up).
- **dev**: sideloaded test build for the user's phone. `DEV_TOOLS = true`: ngrok log streaming, in-app Update
  button, `REQUEST_INSTALL_PACKAGES` + update receivers (`src/dev/AndroidManifest.xml`, `src/dev/.../Updater.kt`).
  `src/play/.../Updater.kt` is a no-op stub with the same API. Build: `gradlew assembleDevDebug`.
- Never put dev-only behavior in `main` without a `BuildConfig.DEV_TOOLS` guard (it's a compile-time constant,
  so R8 strips the dead branch from the Play build). After a Play build, check the dex has no `ngrok`/`PackageInstaller`.

## Environment constraints
- The dev laptop is Intune-managed: **no admin rights**, so nothing can be installed system-wide.
- Portable toolchain lives in `%USERPROFILE%\devtools` (JDK 17 Temurin zip, Android SDK cmdline-tools with
  platforms 35+36 and build-tools 36.0.0, Gradle 8.9). Set `JAVA_HOME` to the `jdk-17*` folder there before
  running `gradlew`. `local.properties` (git-ignored) points `sdk.dir` at `devtools\android-sdk`.
- No Android Studio and no emulator (no rights to enable Windows Hypervisor Platform). Test on a real
  phone via the sideloaded dev APK.

## Updating the installed dev app (user requirement: never uninstall)
- The user updates in place. Every dev build must keep `applicationId = app.speaker.dictate`, be signed with
  `keystore/speaker-debug.keystore` (SHA-256 207e7e1f…1729), and **bump versionCode** for each APK handed over.
  Don't rename the service classes (an enabled accessibility service is tracked by class name).
- Ship: bump versionCode/versionName, `gradlew assembleDevDebug`, copy
  `app/build/outputs/apk/dev/debug/app-dev-debug.apk` to `dist/Speaker-<versionName>-debug.apk`.
  `tools/log-server.mjs` reads the version from `app/build.gradle.kts` and serves `/update/latest.json` + `/update/apk`;
  the user taps **Update** in the app.
- A Play-installed copy is signed by Google's key, so it can't replace the sideloaded dev app (or vice versa)
  without an uninstall. Both share the applicationId.

## Device logs (dev flavor only)
- The user **cannot run script files** on this laptop (Intune). Don't hand them .ps1/.bat workflows; give
  single commands or in-app features instead.
- The user's ngrok free domain is `emmie-unremounted-notedly.ngrok-free.dev` (dev `speakerLogUrl`). The user
  runs `node tools\log-server.mjs` and `ngrok http 8787` in two terminals; read `.ai-workspace/logs/device.log`.
- Only a 2xx from the log server counts as delivered; anything else (ngrok offline 404, 502/503) is retried, so
  logs from an outage arrive later. Never log API keys or auth headers.
- Dictated words go through `spoken(text)` in logs: full text in dev, length only in Play builds.

## Architecture (app/src/main/java/app/speaker/dictate)
- `DictationEngine`: continuous sessions (tap to start, tap to stop). API providers: `SegmentingRecorder` cuts
  phrases at pauses; a single-thread worker transcribes them in order (`onWorker`/`post` are no-ops after
  `release()`). Built-in `SpeechRecognizer`: Android 13+ streams the app-owned mic through `MicPipe`
  (EXTRA_AUDIO_SOURCE, segmented session). One watchdog loop: restarts the recognizer when it closes the pipe,
  stops reporting mic levels for 6 s, or goes 90 s without callbacks. Optional AI rewrite of each phrase.
- `MicPipe`: owns the AudioRecord; reports pipe closed by recognizer, mic silenced by another app, mic failure;
  reopens a dead AudioRecord.
- `BubbleService`: microphone-type foreground service; overlay bubble, notification, optional MediaSession for
  the island/media controls (hardware media buttons are ignored so they can't start the mic).
- `MicTileService`: Quick Settings tile that toggles the running service.
- `TypeAccessibilityService` + `LiveTyper`: types into the focused field via the accessibility InputMethod
  (Android 13+), else ACTION_SET_TEXT / ACTION_PASTE, else clipboard.
- `Transcriber` / `Http`: provider REST calls with `HttpURLConnection` + `org.json` (AI rewrite times out at 20 s).
- `Prefs`: SharedPreferences (excluded from backup and device transfer), provider enums and default models.

## Conventions
- targetSdk 36 means edge-to-edge (no opt-out): the theme has no action bar, and
  `MainActivity.applySystemBarPadding()` pads for system bars + IME. New screens must do the same.
- Edit source files with the Edit tool, or with .NET `[IO.File]` using UTF-8. PowerShell 5.1's
  `Get-Content`/`Set-Content` garble non-ASCII text. Keep LF line endings.
- No third-party dependencies (no AndroidX); keep it that way unless there's a strong reason.
- minSdk 26, targetSdk/compileSdk 36, Kotlin 2.0.21, AGP 8.7.3.
- Default model names go stale; they are user-editable in settings, so update the defaults in `Prefs.kt`.
- The mic foreground service must be started from the foreground (the activity) to keep mic access on
  Android 14+. Don't start it from the tile or background.
- Play policy: keep the accessibility disclosure dialog before opening Accessibility settings, and keep the
  in-app Privacy screen accurate when data flows change (also update `docs/privacy-policy.html`).
