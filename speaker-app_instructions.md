# speaker-app: agent instructions

Android dictation app ("Speaker", package `app.speaker.dictate`). User-facing overview: `README.md`.
Play Store release steps and policy notes: `docs/play-store-release-checklist.md`.

## Branches
- **Main** (this branch): the Google Play version. No server of ours, no self-update, no remote logging,
  no `REQUEST_INSTALL_PACKAGES`, no ngrok anything. This is what gets published.
- **dev**: the testing workflow. It has the same app plus a `dev` build flavor with ngrok live logs to the PC
  (`tools/log-server.mjs`), the in-app Update button that installs new builds from the PC, and the
  sideload signing key. Do day-to-day work and device testing there.
- Moving work to Main: merge `dev` into `Main`. Git already knows Main removed the dev-only files and code,
  so only new dev-only changes need resolving. Never bring back to Main: `app/src/dev`, `app/src/play`,
  `tools/`, `build-apk.ps1`, `dist/`, product flavors, `BuildConfig.DEV_TOOLS`, `speakerLogUrl`, `Updater`,
  log URL / Update UI, or remote log sending in `RemoteLog.kt`.

## Building the release
- `gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`; upload it plus
  `app/build/outputs/mapping/release/mapping.txt` to Play Console.
- Signed with the upload key `keystore/speaker-upload.jks` + `keystore/upload.properties` (git-ignored; back up).
  Without them, the release build is unsigned.
- Bump `versionCode` for every upload. Never change `applicationId`. Don't rename the service classes
  (an enabled accessibility service is tracked by class name).

## Environment constraints
- The dev laptop is Intune-managed: **no admin rights**, so nothing can be installed system-wide.
- Portable toolchain lives in `%USERPROFILE%\devtools` (JDK 17 Temurin zip, Android SDK cmdline-tools with
  platforms 35+36 and build-tools 36.0.0, Gradle 8.9). Set `JAVA_HOME` to the `jdk-17*` folder there before
  running `gradlew`. `local.properties` (git-ignored) points `sdk.dir` at `devtools\android-sdk`.
- No Android Studio and no emulator. Device testing happens on the `dev` branch's sideloaded build.
- The user **cannot run script files** on this laptop. Give single commands or in-app features instead.

## Architecture (app/src/main/java/app/speaker/dictate)
- `DictationEngine`: continuous sessions (tap to start, tap to stop). API providers: `SegmentingRecorder` cuts
  phrases at pauses; a single-thread worker transcribes them in order (`onWorker`/`post` are no-ops after
  `release()`). Built-in `SpeechRecognizer`: Android 13+ streams the app-owned mic through `MicPipe`
  (EXTRA_AUDIO_SOURCE, segmented session). One watchdog loop: restarts the recognizer when it closes the pipe,
  stops reporting mic levels for 6 s, or goes 90 s without callbacks.
  AI rewrite (`draftMode`, fixed per session from `Prefs.polishEnabled`): nothing is typed while you speak.
  Words go out through `onDraft(phrase, isFinal)`; the listener keeps the draft and later calls
  `rewrite(fullText)` once for the whole text (no per-phrase rewriting: chunks lose context). Logs: grep
  `AI REWRITE` (queued/sending/OK/FAILED `cause=`) and `Speaker.ai` (finish/stop reasons, Gemini blocks).
  `BubbleService` draft card (only in AI mode, never shows the AI's text): editable, scrolls after 6 lines;
  tapping the text makes the overlay focusable for the keyboard, an outside touch hands focus back.
  ✨ Send or a bubble tap stops listening and sends; the bubble shows Processing; the result goes through
  `LiveTyper.insert` (focused field, else clipboard). On failure the draft stays for Retry. Bubble long-press =
  quick menu (AI rewrite on/off, Open Speaker). Bubble hidden = the draft is sent when the session ends.
  `RewriteHistory`: last 50 pairs, phone-only, behind `Prefs.keepHistory`; shown in "Recent dictations".
- `MicPipe`: owns the AudioRecord; reports pipe closed by recognizer, mic silenced by another app, mic failure;
  reopens a dead AudioRecord.
- `BubbleService`: microphone-type foreground service; overlay bubble, notification, optional MediaSession for
  the island/media controls (hardware media buttons are ignored so they can't start the mic).
- `MicTileService`: Quick Settings tile that toggles the running service.
- `TypeAccessibilityService` + `LiveTyper`: types into the focused field via the accessibility InputMethod
  (Android 13+), else ACTION_SET_TEXT / ACTION_PASTE, else clipboard.
- `Transcriber` / `Http`: provider REST calls with `HttpURLConnection` + `org.json` (AI rewrite times out at 30 s).
- `Prefs`: SharedPreferences (excluded from backup and device transfer), provider enums and default models.
<<<<<<< HEAD
- `RemoteLog` (name kept to ease merges from dev): local in-memory log ring for Copy/Share logs and the crash
  file. Never sends anything. Dictated words go through `spoken(text)`, which logs only their length.
- `ModelCatalog`: the Model dropdowns. Built-in model list per provider, plus the provider's own list-models
  API (fetched with the user's key, cached in Prefs, filtered to models the app's endpoint can use).
  "Other" lets the user type any name.

## Conventions
- targetSdk 36 means edge-to-edge (no opt-out): the theme has no action bar, and
  `MainActivity.applySystemBarPadding()` pads for system bars + IME. New screens must do the same.
- Edit source files with the Edit tool, or with .NET `[IO.File]` using UTF-8. PowerShell 5.1's
  `Get-Content`/`Set-Content` garble non-ASCII text. Keep LF line endings.
- No third-party dependencies (no AndroidX); keep it that way unless there's a strong reason.
- minSdk 26, targetSdk/compileSdk 36, Kotlin 2.0.21, AGP 8.7.3.
- Default model names go stale. Update the defaults in `Prefs.kt` and the built-in lists in `ModelCatalog.kt`
  (users also get the live list via Refresh).
- The mic foreground service must be started from the foreground (the activity) to keep mic access on
  Android 14+. Don't start it from the tile or background.
- Play policy: keep the accessibility disclosure dialog before opening Accessibility settings, and keep the
  in-app Privacy screen accurate when data flows change (also update `docs/privacy-policy.html`).
