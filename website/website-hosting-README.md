# Speaker website: hosting and updating

Static site: plain HTML and CSS, no build step, no JavaScript, no cookies. Pages: `index.html`, `download.html`,
`guide.html`, `faq.html`, `privacy.html`. Shared files are in `assets/`. The APK goes in
`downloads/Speaker-<version>.apk`. APKs are git-ignored, so copy the file in before you upload.

## Before going live: fill in the placeholders

Find and replace in every `.html` file:

| Placeholder        | Replace with                                 |
| ------------------ | -------------------------------------------- |
| `[DEVELOPER NAME]` | Your name or business name                   |
| `[CONTACT EMAIL]`  | A support address you're happy to publish    |
| `[DATE]`           | Privacy policy effective date (privacy.html) |

Make the same changes in `docs/privacy-policy.html`, so the copy used for Play matches.

## Hosting on a Namecheap subdomain (shared hosting / cPanel)

1. Namecheap → Hosting List → **Go to cPanel**.
2. cPanel → **Domains** → **Create A New Domain**. Enter e.g. `speaker.yourdomain.com`, untick "Share document
   root", and accept the suggested folder (e.g. `speaker.yourdomain.com`). If your domain uses Namecheap
   BasicDNS, the DNS record is created for you.
3. cPanel → **File Manager** → open that folder → **Upload** the website zip → right-click it → **Extract**.
   `index.html` must sit directly in the folder, not in a subfolder. Delete the zip afterwards.
4. Turn on **Show Hidden Files** (File Manager → Settings) to check that `.htaccess` is there. It makes phones
   treat the APK as an app to install.
5. cPanel → **SSL/TLS Status** → tick the subdomain → **Run AutoSSL**. Once it shows a green lock, uncomment the
   three `Rewrite` lines in `.htaccess` to force HTTPS.

### If you only have the domain at Namecheap (no hosting plan)

Host the folder on GitHub Pages, Cloudflare Pages or Netlify (all free for static sites). Then, in Namecheap →
Domain List → **Manage** → **Advanced DNS**, add a `CNAME` record: host `speaker`, value the address the host
gives you (e.g. `<user>.github.io`). `.htaccess` is ignored there; those hosts already serve `.apk` correctly.

## Releasing a new version

1. Bump `versionCode`/`versionName` in `app/build.gradle.kts`, then run `gradlew assemblePlayRelease`.
2. Copy `app/build/outputs/apk/play/release/app-play-release.apk` to `website/downloads/Speaker-<version>.apk`.
3. Get the SHA-256 and the size: `certutil -hashfile website\downloads\Speaker-<version>.apk SHA256`.
4. Update every page: the version (masthead, specs panel, download table and button), the file name in
   `download.html`, the size, the release date, the SHA-256, and a line in "What's New" on `index.html`.
5. Upload the new APK and the changed pages. Remove the old APK.

The public APK is the **play** flavor signed with the upload key (`keystore/speaker-upload.jks`, certificate
SHA-256 `52:5B:2F:…:D5:92`). Always sign website builds with that key, or people can't update in place. It can't
replace the sideloaded dev build on your own phone, which uses the debug key.

When you set up Google Play: by default, Play App Signing re-signs the app with a Google-held key, so website
users would have to uninstall before installing from Play. To avoid that, choose to use your own app signing key
and upload this same key when you enroll.
