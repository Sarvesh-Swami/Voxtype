# speaker-app: agent instructions

Android dictation app ("Speaker", package `app.speaker.dictate`). User-facing overview: `README.md`.

## Environment constraints
- The dev laptop is Intune-managed: **no admin rights**, so nothing can be installed system-wide.
- Portable toolchain lives in `%USERPROFILE%\devtools` (JDK 17 Temurin zip, Android SDK cmdline-tools,
  Gradle 8.9). Set `JAVA_HOME` to the `jdk-17*` folder there before running `gradlew`.
  `local.properties` (git-ignored) points `sdk.dir` at `devtools\android-sdk`.
- No Android Studio and no emulator (no rights to enable Windows Hypervisor Platform). Test on a real
  phone via sideloaded APK or adb wireless debugging.
- Build: `.\build-apk.ps1` → `dist\Speaker-<version>-debug.apk`.

## Updating the installed app (user requirement: never uninstall)
- The user updates in place. Every build must keep `applicationId = app.speaker.dictate`, be signed with
  `keystore/speaker-debug.keystore` (same cert as v0.1.0, SHA-256 207e7e1f…1729), and **bump versionCode**
  in `app/build.gradle.kts` for each APK handed over. Don't rename the service classes either (an enabled
  accessibility service is tracked by class name). Keep old APKs in `dist/`.

## Shipping a build (in-app self-update)
- Bump versionCode/versionName, build, and copy the APK to `dist/Speaker-<versionName>-debug.apk`. That's all:
  `tools/log-server.mjs` reads the version from `app/build.gradle.kts` on every request and serves
  `/update/latest.json` + `/update/apk` through the ngrok URL; the user taps **Check for update** in the app.
- App side: `Updater.kt` (PackageInstaller session, `UpdateReceiver` for the confirm screen,
  `PackageReplacedReceiver` reopens the app after updating).

## Device logs
- The user **cannot run script files** on this laptop (Intune). Don't hand them .ps1/.bat workflows; give
  single commands or in-app features instead.
- The user's ngrok free domain is `emmie-unremounted-notedly.ngrok-free.dev` (baked in as speakerLogUrl). The user
  runs `node tools\log-server.mjs` and `ngrok http 8787` in two terminals; read `.ai-workspace/logs/device.log`.
- Log paths: in-app **Copy/Share logs** (last 1500 lines), or live via a hand-started `ngrok http 8787`. The app
  POSTs plain-text batches to the log URL (default baked in from `speakerLogUrl` in gradle.properties);
  the user copies them from the ngrok inspector (127.0.0.1:4040) into chat. Any HTTP reply (including ngrok's 502)
  counts as delivered. The app side is `RemoteLog.kt` (own-PID logcat + crash file). Never log API keys or auth headers.

## Architecture (app/src/main/java/app/speaker/dictate)
- `DictationEngine`: continuous sessions (tap to start, tap to stop; never auto-stops). API providers:
  `SegmentingRecorder` cuts phrases at ~0.9 s pauses (energy VAD), and a single-thread executor transcribes
  them in order. Built-in `SpeechRecognizer` is restarted in a loop. Shared by `BubbleService` and `MainActivity`.
- `BubbleService`: microphone-type foreground service; overlay bubble + notification actions.
- `MicTileService`: Quick Settings tile that toggles the running service.
- `TypeAccessibilityService` + `Delivery`: inserts text into the focused field (ACTION_SET_TEXT, then
  ACTION_PASTE fallback); always copies to the clipboard first.
- `Transcriber` / `Http`: provider REST calls with `HttpURLConnection` + `org.json`.
- `Prefs`: SharedPreferences, provider enums and default model names.

## Conventions
- targetSdk 35 means edge-to-edge on Android 15+: the theme has no action bar, and `MainActivity.applySystemBarPadding()`
  pads the root for system bars + IME. New screens must do the same, or their content hides behind the bars.
- Edit source files with the Edit tool, or with .NET `[IO.File]` using UTF-8. PowerShell 5.1's `Get-Content`/`Set-Content`
  garble the non-ASCII text (emoji, curly quotes) in these files.
- No third-party dependencies (no AndroidX); keep it that way unless there's a strong reason.
- minSdk 26, targetSdk/compileSdk 35, Kotlin 2.0.21, AGP 8.7.3.
- Default model names go stale; they are user-editable in settings, so update the defaults in `Prefs.kt`.
- The mic foreground service must be started from the foreground (the activity) to keep mic access on
  Android 14+. Don't start it from the tile or background.
