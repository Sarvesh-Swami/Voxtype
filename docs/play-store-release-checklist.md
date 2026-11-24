# VoxType: Google Play release checklist

Build to upload: `app/build/outputs/bundle/release/app-release.aab` (from `gradlew bundleRelease` on the Main branch).
Also upload `app/build/outputs/mapping/release/mapping.txt` (Play Console → App bundle explorer → deobfuscation file).

## 1. One-time account and app setup
- [ ] Turn on **Play App Signing** (default). Upload key: `keystore/speaker-upload.jks`; its password is in
      `keystore/upload.properties`. Back up both somewhere safe outside the repo.
- [ ] New **personal** developer accounts must run a **closed test with at least 12 testers for 14 days**
      before production access. Plan for that.
- [x] Privacy policy hosted at https://voxtype.sarveshh.me/privacy.html (website repo, same text as
      `docs/privacy-policy.html`; developer Sarvesh Swami, contact.sarvesh.swami@gmail.com, effective 24 November 2026)
      and set in `app/src/main/res/values/strings.xml` → `privacy_policy_url` (enables the "Full policy" button).
- [ ] Put the same URL in Play Console → App content → Privacy policy.

## 2. Play Console declarations (App content)
| Item | Answer |
|---|---|
| Privacy policy | Your hosted URL. |
| Ads | No ads. |
| App access | All functionality available without login. In the instructions, say that the reviewer has to allow Microphone, Display over other apps and the VoxType auto-type Accessibility service from the in-app checklist. The built-in engine needs no API key. |
| Content rating | Utility / productivity questionnaire, no objectionable content. |
| Target audience | 13+ (or 18+). Not for children. |
| **Accessibility API** | Declare the AccessibilityService. Purpose: types dictated speech into the focused text field for the user (voice input). Not an accessibility tool (`isAccessibilityTool=false`). The app shows a prominent disclosure and asks for consent before opening the setting. Attach a short video: open app → Enable auto-typing → disclosure → settings → dictate into a chat app. |
| **Foreground service** | Type `microphone`. Use: user-started continuous dictation from a floating button / notification; the user can see and stop it. Attach a video showing the bubble, the notification and stopping it. |
| Data safety | See section 3. |
| Government / financial / health | None. |

## 3. Data safety form (suggested answers)
The developer receives nothing. Data only goes directly from the phone to providers the user chooses. Google
counts transmitting data off the device as "collection", so declare it to be safe:
- **Audio → Voice or sound recordings**: collected (only when the user selects an API engine), not shared,
  processed ephemerally, **optional**, purpose = App functionality.
- **Messages / text → Other in-app messages** (dictated text sent for AI rewrite): collected, optional,
  ephemeral, App functionality.
- Encrypted in transit: **Yes** (HTTPS only; cleartext traffic is blocked).
- Users can request deletion: data isn't stored by the developer. Explain "uninstall removes all local data".
- Not collected: location, contacts, identifiers, app activity, diagnostics (logs are only shared manually).

## 4. Store listing assets you still need to make
- [ ] App icon 512×512 PNG (use the launcher icon: violet→blue gradient, white mic + waves).
- [ ] Feature graphic 1024×500.
- [ ] At least 2 phone screenshots (main screen, bubble over a chat app, AI rewrite dialog).
- [ ] Short description (80 chars), full description. Don't name other brands in the title. Mention that AI
      providers need the user's own API key.

## 5. Before every upload
- [ ] Bump `versionCode` (and `versionName`) in `app/build.gradle.kts`. It must be higher than any
      `devVersionCode` used for phone tests (in the private `dev-local/dev.properties`).
- [ ] `gradlew bundleRelease lintRelease` with 0 lint errors.
- [ ] Verify the release has no dev code: `aapt2 dump badging` on `app-release.apk` shows no
      `REQUEST_INSTALL_PACKAGES`, the dex contains no `ngrok` / `PackageInstaller` / `UpdateReceiver` strings, and it's
      signed with the upload key. Once in a while, also build with `dev-local/` renamed to prove the repo builds without it.
- [ ] Test the same code as a debug build on a phone: bubble, tile, notification, island, built-in engine, one API
      engine, AI rewrite, Termux, WhatsApp.

## 6. Known review risks (not hard blockers)
- **Accessibility review is strict.** Typing for the user is an accepted use, but reviewers can still reject
  if the declaration or video is unclear. The safest long-term option is to also ship VoxType as a voice
  **keyboard** (InputMethodService), which needs no accessibility permission.
- **Island / media controls** show dictation as a media session. Hardware media buttons are ignored (they can't
  start the mic). If a reviewer objects, turn the default to off in `Prefs.islandMedia`.
- **Beep muting**: with the fallback restart-loop recognizer, VoxType mutes the system/notification/music
  streams during dictation and restores them afterwards (also after a crash). This is disclosed nowhere yet;
  consider a line in the description.
- Check the app name "VoxType" doesn't clash with an existing Play listing or trademark before creating the app.
