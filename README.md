 

# Speaker: dictate into any Android app

A small Android app that floats a mic bubble over every app. Tap it, speak, tap again, and your
speech is transcribed and typed into whatever text field you're in, or copied to the clipboard
when the app doesn't accept typing (e.g. Termux).

Dictation is **continuous**: one tap starts it, and it keeps typing phrase by phrase (at each pause)
at the current cursor until you tap again. It never stops on its own when you go quiet.

## Ways to trigger dictation

| Trigger             | How                                                                         |
| ------------------- | --------------------------------------------------------------------------- |
| Floating bubble     | Tap = start/stop · drag = move · long-press = open settings               |
| Notification        | The persistent "Speaker" notification has a **Dictate / Stop** button |
| Quick Settings tile | Edit Quick Settings, add **Dictate**, then tap it from any app        |
| In-app              | Big **Tap to speak** button (works in split-screen)                   |

Android has no public "Dynamic Island" API; the bubble, notification and tile are the equivalents.

## Speech-to-text engines (bring your own key)

| Engine                   | Default model              | Key needed                |
| ------------------------ | -------------------------- | ------------------------- |
| Android built-in         | (device recognizer)        | No                        |
| OpenAI                   | `gpt-4o-mini-transcribe` | Yes                       |
| Groq                     | `whisper-large-v3-turbo` | Yes (free tier available) |
| Google Gemini            | `gemini-2.5-flash`       | Yes                       |
| Deepgram                 | `nova-3`                 | Yes                       |
| Custom OpenAI-compatible | `whisper-1`              | Yes (plus base URL)       |

Optional **LLM polish** step (punctuation and filler-word cleanup, editable prompt): OpenAI, Anthropic
Claude, Gemini, Groq or a custom OpenAI-compatible endpoint. Every model name is editable in
settings, so you can switch to newer models without rebuilding.

Keys are stored in the app's private storage on the phone and sent only to their own provider.

## Install on your phone

1. Copy `dist/Speaker-0.1.0-debug.apk` to the phone (USB, Drive, email, etc.) and open it. Allow
   "install unknown apps" for the app you opened it with.
2. Open **Speaker**, then:
   1. **Allow microphone**
   2. **Allow display over other apps** (for the bubble)
   3. **Enable auto-typing** (optional Accessibility service). On Android 13+ the switch is greyed
      out for sideloaded apps: go to *Settings → Apps → Speaker → ⋮ → Allow restricted settings*,
      then enable it.
3. Choose an engine, paste your API key, then **Start floating bubble**.

### Updating (keeps permissions and settings)

From v0.5.0: with `node tools\log-server.mjs` and ngrok running on the PC, tap **Check for update** at the top
of the app. It downloads the newest APK from `dist/` and Android asks you to confirm. The first time, Android
asks you to allow "Install unknown apps" for Speaker.

Or open the newer APK on the phone and tap **Update**. Do not uninstall first. Updates work because
every build is signed with the same key (`keystore/speaker-debug.keystore`; back it up) and has a
higher `versionCode`. After an update, tap **Start floating bubble** again (Android stops running
services when an app updates).

### Termux note

Termux's terminal isn't a standard text field, so auto-typing usually can't reach it. In that case
the whole session's text is kept on the clipboard: when you stop, long-press in Termux and choose **Paste**.

## Live logs from the phone to the PC

**No setup:** in the app, tap **Copy logs** (or **Share logs**) under *Debug logs* and paste them anywhere.

**Live in a PC terminal, through ngrok:** open two terminals in the project folder:

```
node tools\log-server.mjs                     # window 1: prints every log line live
%LOCALAPPDATA%\ngrok\ngrok.exe http 8787      # window 2: public URL for the phone
```

Every tap, setting change, dictation phrase, provider reply, error and crash appears in window 1 and is also
saved to `.ai-workspace/logs/device.log`. Without window 1, ngrok's inspector at `http://127.0.0.1:4040` still
shows each batch (ngrok answers 502 then; the app keeps those lines and resends them once the receiver answers 2xx).

The app sends to the URL in *Debug logs*, which you can type on the phone. To skip typing, bake the URL into the
build: set `speakerLogUrl=https://<your-domain>.ngrok-free.app/log` in `gradle.properties` and rebuild. Free
ngrok accounts keep the same domain across runs. API keys are never logged.

`tools/log-server.mjs` is an optional receiver that writes logs to a file (`node tools/log-server.mjs`).

## Build (no admin rights, no Android Studio)

Toolchain (portable, under `%USERPROFILE%\devtools`): Temurin JDK 17, Android SDK command-line tools
(platform 35, build-tools 35.0.0, platform-tools), Gradle 8.9 (the project's `gradlew` wrapper pins it).

```powershell
.\build-apk.ps1            # -> dist\Speaker-<version>-debug.apk
.\build-apk.ps1 -Install   # also installs over adb (USB or Wireless debugging)
```

Wireless debugging (no USB driver needed): on the phone, open *Developer options → Wireless debugging
→ Pair device with pairing code*, then on the PC run
`adb pair <ip:port>` followed by `adb connect <ip:port>` (adb is in `devtools\android-sdk\platform-tools`).

## Google Play build

There are two flavors. **dev** (`gradlew assembleDevDebug`) is the sideloaded test build with the live logs and the
Update button described above. **play** (`gradlew bundlePlayRelease`) is what goes to Google Play: no server, no
self-update, no remote logging. Release steps and policy notes: `docs/play-store-release-checklist.md`; privacy
policy to host: `docs/privacy-policy.html`.
