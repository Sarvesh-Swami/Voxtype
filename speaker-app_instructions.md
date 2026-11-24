# speaker-app: agent instructions

Android dictation app ("Speaker", package `app.speaker.dictate`). User-facing overview: `README.md`.
Play Store release steps and policy notes: `docs/play-store-release-checklist.md`.
The public website is a separate repo: `D:\Projects\speaker-website` (GitHub Pages on a Namecheap subdomain; see its
`README.md` and `speaker-website_instructions.md`). Keep its `src/privacy-policy.html` the same as
`docs/privacy-policy.html` here.

## One codebase, private dev tools kept local
- **`dev` and `Main` hold the same code.** Everything committed is the Play-safe app. Work on `dev`, then bring
  `Main` up to it (fast-forward: `git fetch . dev:Main`). There are no dev-only files in git any more, so merges
  between them can't conflict. Commit locally only; the user pushes from their own account.
- **Private dev tools live in `dev-local/`** at the repo root. It's git-ignored (never commit or `git add -f` it)
  and exists only on the user's laptop, so it must be backed up outside git:
  - `dev-local/android/AndroidManifest.xml` + `android/java/.../Updater.kt`: the sideload Update button
    (`REQUEST_INSTALL_PACKAGES`, `UpdateReceiver`, `PackageReplacedReceiver`, PackageInstaller).
  - `dev-local/dev.properties`: `speakerLogUrl` (ngrok log URL) and `devVersionCode` (debug versionCode).
  - `dev-local/tools/log-server.mjs` (+ `ngrok-log-policy.yml`): log receiver and update server.
  - `dev-local/speaker-dev-workflow-README.md`: the user's dev notes. `dev-local/build-apk.ps1`: old helper.
- **How the build uses it (`app/build.gradle.kts`):**
  - **debug** + `dev-local/android/` present: adds that code and manifest, `BuildConfig.DEV_TOOLS = true`, log URL
    and `devVersionCode` from `dev.properties`, versionName suffix `-dev`.
  - **debug** without `dev-local/` (fresh clone, CI): plain build using the no-op `app/src/nodev/.../Updater.kt`.
  - **release**: always `src/nodev`, `DEV_TOOLS = false`, blank log URL, upload key only. Never touches `dev-local/`.
- Shared code may only reach dev tools through APIs that also exist in `src/nodev` (same `Updater` API), or
  behind `BuildConfig.DEV_TOOLS` (a compile-time constant, so R8 strips the dead branch from release builds).
  Anything Play forbids (self-update, install permission) belongs in `dev-local/android/`, never in `src/main`.
- Before a Play upload, check the release: no `REQUEST_INSTALL_PACKAGES` in `aapt2 dump badging`, no
  `ngrok`/`PackageInstaller`/`UpdateReceiver` in the dex, signed with the upload key. Now and then, rename
  `dev-local` and build `assembleDebug bundleRelease` to prove the repo builds without it.

## Builds
- **Phone test build:** `gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
- **Play:** `gradlew bundleRelease lintRelease` → `app/build/outputs/bundle/release/app-release.aab` (upload it plus
  `app/build/outputs/mapping/release/mapping.txt`). Signed with the upload key `keystore/speaker-upload.jks` +
  `keystore/upload.properties` (git-ignored; back up). Without them, the release is unsigned (never the debug key).
- Shared `versionCode`/`versionName` in `app/build.gradle.kts` are for Play releases; each upload must be higher than
  every `devVersionCode` used before.

## Environment constraints
- The dev laptop is Intune-managed: **no admin rights**, so nothing can be installed system-wide.
- Portable toolchain lives in `%USERPROFILE%\devtools` (JDK 17 Temurin zip, Android SDK cmdline-tools with
  platforms 35+36 and build-tools 36.0.0, Gradle 8.9). Set `JAVA_HOME` to the `jdk-17*` folder there before
  running `gradlew`. `local.properties` (git-ignored) points `sdk.dir` at `devtools\android-sdk`.
- No Android Studio and no emulator (no rights to enable Windows Hypervisor Platform). Test on a real
  phone via the sideloaded debug APK.

## Updating the installed dev app (user requirement: never uninstall)
- The user updates in place. Every debug build must keep `applicationId = app.speaker.dictate`, be signed with
  `keystore/speaker-debug.keystore` (SHA-256 207e7e1f…1729), and **raise `devVersionCode`** in
  `dev-local/dev.properties` for each APK handed over. Don't rename the service classes (an enabled
  accessibility service is tracked by class name).
- Ship: raise `devVersionCode`, `gradlew assembleDebug`, copy `app/build/outputs/apk/debug/app-debug.apk` to
  `dist/Speaker-<versionName>-debug.apk` (`dist/` is git-ignored). `dev-local/tools/log-server.mjs` reads
  `devVersionCode` (else the shared versionCode) and serves `/update/latest.json` + `/update/apk`; the user taps
  **Update** in the app.
- A Play-installed copy is signed by Google's key, so it can't replace the sideloaded dev app (or vice versa)
  without an uninstall. Both share the applicationId.

## Device logs (debug builds with dev-local only)
- The user **cannot run script files** on this laptop (Intune). Don't hand them .ps1/.bat workflows; give
  single commands or in-app features instead.
- The user runs `node dev-local\tools\log-server.mjs` and `ngrok http 8787` in two terminals; read
  `.ai-workspace/logs/device.log`. The ngrok URL lives only in `dev-local/dev.properties`.
- Only a 2xx from the log server counts as delivered; anything else (ngrok offline 404, 502/503) is retried, so
  logs from an outage arrive later. Never log API keys or auth headers.
- Dictated words go through `spoken(text)` in logs: full text with DEV_TOOLS, length only in release builds.

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
  quick menu (AI rewrite on/off, Prompt switcher, Open Speaker). Bubble hidden = the draft is sent when the session ends.
  `RewriteHistory`: last 50 pairs, phone-only, behind `Prefs.keepHistory`; shown in "Recent dictations".
- `MicPipe`: owns the AudioRecord; reports pipe closed by recognizer, mic silenced by another app, mic failure;
  reopens a dead AudioRecord.
- `BoyView`: the floating bubble's character on a transparent 54x96dp window: a little big-headed (chibi) guy in a baggy green hoodie and loose joggers (folds, bunched hood, drawstrings), side view,
  leaning back on the screen edge with legs crossed (Canvas paths in a 60x108 box, mirrored on the right edge).
  VIBING: headphones on, laptop closed at his side (goes still after 30 s). LISTENING: headphones to the neck, raises
  and opens the laptop (only the lid's back is visible), screen light on his face, types on `onVoice(true)`/`nudge()`.
  THINKING: same pose, light pulses. Prototype for visual checks: `.ai-workspace/scratch/chibi-boy.html`.
- `BubbleService`: microphone-type foreground service; overlay bubble, notification, optional MediaSession for
  the island/media controls (hardware media buttons are ignored so they can't start the mic).
- `MicTileService`: Quick Settings tile that toggles the running service.
- `TypeAccessibilityService` + `LiveTyper`: types into the focused field via the accessibility InputMethod
  (Android 13+), else ACTION_SET_TEXT / ACTION_PASTE, else clipboard.
- `Prompts` + `Prefs.savedPrompts`: AI rewrite prompt library. Built-in presets plus the user's named prompts (JSON in prefs,
  max 30). The active prompt is just `Prefs.polishPrompt`; its name is found by matching text (edited by hand = "Custom").
  Managed in the app's prompt dialog: one tab row (＋ Custom, ★ saved, presets; selected tab highlighted). Custom shows
  an inline Name field + "Save to my prompts"; long-press a ★ tab to rename/delete. Editing a preset's text makes it Custom and
  switchable from the bubble's quick menu (📝 Prompt). `MainActivity.onResume` re-reads AI on/off and the prompt so
  changes made from the bubble aren't overwritten by `saveSettings()`.
- `Transcriber` / `Http`: provider REST calls with `HttpURLConnection` + `org.json` (AI rewrite times out at 30 s).
- `Prefs`: SharedPreferences (excluded from backup and device transfer), provider enums and default models.
- `ModelCatalog`: the Model dropdowns. Built-in model list per provider, plus the provider's own list-models
  API (fetched with the user's key, cached in Prefs, filtered to models the app's endpoint can use).
  "Other" lets the user type any name.

## Conventions
- targetSdk 36 means edge-to-edge (no opt-out): the theme has no action bar, and
  `MainActivity.applySystemBarPadding()` pads for system bars + IME. New screens must do the same.
- Edit source files with the Edit tool, or with .NET `[IO.File]` using UTF-8. PowerShell 5.1's
  `Get-Content`/`Set-Content` garble non-ASCII text. Keep LF line endings.
- The `Field` style must keep its `android:Widget.Material.Light.EditText` parent. Code-made text boxes use
  `EditText(ctx, null, 0, R.style.Field)`, which skips the theme's defaults; without the parent they aren't focusable and
  the keyboard never opens.
- No third-party dependencies (no AndroidX); keep it that way unless there's a strong reason.
- minSdk 26, targetSdk/compileSdk 36, Kotlin 2.0.21, AGP 8.7.3.
- Default model names go stale. Update the defaults in `Prefs.kt` and the built-in lists in `ModelCatalog.kt`
  (users also get the live list via Refresh).
- The mic foreground service must be started from the foreground (the activity) to keep mic access on
  Android 14+. Don't start it from the tile or background.
- Play policy: keep the accessibility disclosure dialog before opening Accessibility settings, and keep the
  in-app Privacy screen accurate when data flows change (also update `docs/privacy-policy.html`).
