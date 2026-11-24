# VoxType: voice typing for every Android app

VoxType floats a mic bubble over every app. Tap it, talk, and your words are typed live into whatever
text field you're in: chats, email, notes, even terminals like Termux. Tap again to stop.

Dictation is **continuous**: one tap starts it, and it keeps typing as you speak, pauses included,
until you tap again or leave the text field.

## Features

- **Floating character** over every app, leaning on the screen edge. Tap = start/stop, drag = move,
  long-press = quick menu. While you talk he pulls his headphones down, opens his laptop and types.
- **Characters**: pick who floats on your screen: Vox, Shade, Noir, Kitsune or Volt. All free.
- **Island / media controls**: start and stop from your phone's island, notification shade or lock
  screen (▶ = dictate, ⏸ = stop).
- **Notification** and **Quick Settings tile** ("Dictate").
- **Live typing**: words appear as you speak and are corrected as the recognizer refines them.
- **AI rewrite** (optional): your words collect in an editable draft; tap **Send** and the whole draft is
  rewritten once with your prompt ("fix my grammar", "make it professional", "translate to English"...).
  Pick a preset, or write and save your own prompts, and switch them from the app or the bubble's menu.
- **No account, no servers, no ads, no analytics.** Your keys and settings stay on your phone.

## Speech engines (bring your own key)

| Engine | Default model | Key needed |
|---|---|---|
| Android built-in | (your phone's recognizer) | No |
| OpenAI | `gpt-4o-mini-transcribe` | Yes |
| Groq | `whisper-large-v3-turbo` | Yes (free tier available) |
| Google Gemini | `gemini-2.5-flash` | Yes |
| Deepgram | `nova-3` | Yes |
| Custom OpenAI-compatible | `whisper-1` | Yes (plus base URL) |

AI rewrite providers: OpenAI, Anthropic Claude, Google Gemini, Groq, or a custom OpenAI-compatible
endpoint. Every model name is editable in settings, so newer models work without an app update.

API keys are stored only in the app's private storage (excluded from backups and phone-to-phone
transfer) and are sent only to the provider each key belongs to.

## Getting started

1. Install VoxType from Google Play and open it.
2. Follow the **Finish setup** checklist:
   1. **Microphone**: to hear you.
   2. **Display over other apps**: for the floating bubble.
   3. **Auto-typing**: VoxType explains what it does, then opens Accessibility settings; turn on
      **VoxType auto-type**. Without it, dictation is copied to the clipboard so you can paste it.
   4. **Run in background** (recommended): stops the phone from closing the bubble.
3. Optional: choose an engine and paste your API key, or turn on **AI rewrite**.
4. Tap **Start floating bubble**, open any app, tap a text field, then tap the bubble and talk.

## Privacy

VoxType has no servers; the developer receives nothing. Audio is processed by your phone's speech
service (built-in engine) or sent directly from your phone to the provider you chose. See
[docs/privacy-policy.html](docs/privacy-policy.html) and the in-app **Privacy & permissions** screen.

## Build from source

Requirements: JDK 17 and the Android SDK (platform 36, build-tools 36.0.0). No Android Studio needed;
the Gradle wrapper pins Gradle 8.9. No third-party libraries.

```powershell
.\gradlew.bat assembleDebug     # debug APK: app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat bundleRelease     # Play bundle: app\build\outputs\bundle\release\app-release.aab
```

`bundleRelease` signs with the Play upload key in `keystore/` (`speaker-upload.jks` + `upload.properties`,
git-ignored). Release steps and Play Console declarations: [docs/play-store-release-checklist.md](docs/play-store-release-checklist.md).

Release builds contain only what's in this repository. The developer's private test tools (live logs to a
PC, sideload updater) live in a git-ignored `dev-local/` folder that only debug builds use when it's present.
