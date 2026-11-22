# Builds the XP-style Speaker website pages (shared window chrome, task pane, taskbar, Start menu).
import pathlib, re

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "website"
MIC = '<svg viewBox="0 0 24 24"><path fill="#fff" d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z"/></svg>'

PAGES = [
    ("index.html", "Welcome", "assets/logo.svg"),
    ("download.html", "Download", "assets/ic-download.svg"),
    ("guide.html", "Setup Guide", "assets/ic-help.svg"),
    ("faq.html", "FAQ", "assets/ic-doc.svg"),
    ("privacy.html", "Privacy", "assets/ic-shield.svg"),
]

def chrome(file, title, doc_title, desc, path_text, pane, body, status):
    menu = "".join(
        f'<li><a href="{f}"{" aria-current=\"page\"" if f == file else ""}>{n}</a></li>' for f, n, _ in PAGES)
    tasks = "".join(
        f'<a class="task{" on" if f == file else ""}" href="{f}"><img src="{i}" alt="">{n}</a>' for f, n, i in PAGES)
    return f'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{doc_title}</title>
<meta name="description" content="{desc}">
<link rel="icon" href="assets/logo.svg" type="image/svg+xml">
<link rel="stylesheet" href="assets/style.css">
</head>
<body>

<nav class="desk" aria-label="Shortcuts">
  <a href="downloads/Speaker-1.0.0.apk" download><img src="assets/logo.svg" alt=""><span>Speaker-1.0.0.apk</span></a>
  <a href="guide.html"><img src="assets/ic-help.svg" alt=""><span>Setup Guide</span></a>
  <a href="faq.html"><img src="assets/ic-folder.svg" alt=""><span>FAQ</span></a>
  <a href="privacy.html"><img src="assets/ic-shield.svg" alt=""><span>Privacy Policy</span></a>
  <a href="mailto:[CONTACT EMAIL]"><img src="assets/ic-mail.svg" alt=""><span>Contact</span></a>
</nav>

<div class="window">
  <div class="titlebar">
    <img src="assets/logo.svg" alt="">
    <h1>{title}</h1>
    <div class="ctl" aria-hidden="true"><span class="min"></span><span class="max"></span><span class="close"></span></div>
  </div>
  <ul class="menubar">{menu}<li class="brand-mark">Speaker 1.0.0</li></ul>
  <div class="toolbar">
    <a class="tb" href="index.html"><img src="assets/logo.svg" alt="">Home</a>
    <a class="tb" href="download.html"><img src="assets/ic-download.svg" alt="">Download</a>
    <span class="tb-sep"></span>
    <a class="tb" href="guide.html"><img src="assets/ic-help.svg" alt="">Help</a>
    <a class="tb" href="faq.html"><img src="assets/ic-folder.svg" alt="">FAQ</a>
    <a class="tb" href="privacy.html"><img src="assets/ic-shield.svg" alt="">Privacy</a>
  </div>
  <div class="addressbar">Address
    <div class="address"><img src="assets/logo.svg" alt="">{path_text}</div>
    <a class="go" href="{file}"><i></i>Go</a>
  </div>
  <div class="client">
    <aside class="taskpane">
{pane}
    </aside>
    <main class="content">
{body}
    </main>
  </div>
  <div class="statusbar"><span>{status}</span><span>&copy; 2026 [DEVELOPER NAME]</span><span><img src="assets/ic-shield.svg" alt="">No tracking</span></div>
</div>

<div class="taskbar">
  <details class="start">
    <summary><img src="assets/logo.svg" alt="">start</summary>
    <div class="startmenu">
      <div class="sm-head"><img src="assets/logo.svg" alt="">Speaker</div>
      <div class="sm-cols">
        <div class="sm-left">
          <a href="downloads/Speaker-1.0.0.apk" download><img src="assets/ic-download.svg" alt=""><span><b>Download Speaker</b><small>Version 1.0.0 &middot; 137 KB</small></span></a>
          <a href="guide.html"><img src="assets/ic-help.svg" alt=""><span><b>Setup Guide</b><small>Get started step by step</small></span></a>
          <a href="faq.html"><img src="assets/ic-folder.svg" alt=""><span><b>FAQ</b><small>Answers to common questions</small></span></a>
          <a href="index.html#features"><img src="assets/ic-spark.svg" alt=""><span><b>Features</b><small>What Speaker can do</small></span></a>
        </div>
        <div class="sm-right">
          <a href="index.html"><img src="assets/logo.svg" alt="">Welcome</a>
          <a href="download.html#releases"><img src="assets/ic-doc.svg" alt="">Release Notes</a>
          <a href="download.html#requirements"><img src="assets/ic-phone.svg" alt="">Requirements</a>
          <a href="privacy.html"><img src="assets/ic-shield.svg" alt="">Privacy Policy</a>
          <a href="mailto:[CONTACT EMAIL]"><img src="assets/ic-mail.svg" alt="">Contact</a>
        </div>
      </div>
      <div class="sm-foot">Speaker for Android</div>
    </div>
  </details>
  <div class="tasks">{tasks}</div>
  <div class="tray"><img src="assets/ic-shield.svg" alt=""><img src="assets/logo.svg" alt=""><span id="clock">12:00 PM</span></div>
</div>
<script>
(function () {{
  var c = document.getElementById("clock");
  function t() {{ var d = new Date(), h = d.getHours(), m = d.getMinutes();
    c.textContent = ((h % 12) || 12) + ":" + (m < 10 ? "0" : "") + m + (h < 12 ? " AM" : " PM"); }}
  t(); setInterval(t, 15000);
}})();
</script>
</body>
</html>
'''

def pane(*boxes):
    out = []
    for i, (head, inner) in enumerate(boxes):
        cls = "tp special" if i == 0 else "tp"
        out.append(f'      <div class="{cls}"><div class="tp-h">{head}</div><div class="tp-b">{inner}</div></div>')
    return "\n".join(out)

def links(*items):
    return "<ul>" + "".join(f'<li><a href="{h}"{" download" if h.endswith(".apk") else ""}><img src="{i}" alt="">{t}</a></li>' for h, i, t in items) + "</ul>"

DETAILS = ("Details", '<div class="detail-name">Speaker 1.0.0</div><div class="detail">Android app (APK)<br>Size: 137 KB<br>Requires: Android 8.0+<br>Released: 22 Nov 2026<br>Price: Free</div>')
OTHER = ("Other Places", links(("index.html", "assets/logo.svg", "Welcome"), ("download.html", "assets/ic-download.svg", "Download"),
                             ("guide.html", "assets/ic-help.svg", "Setup Guide"), ("faq.html", "assets/ic-folder.svg", "FAQ"),
                             ("privacy.html", "assets/ic-shield.svg", "Privacy Policy")))
TASKS = ("Speaker Tasks", links(("downloads/Speaker-1.0.0.apk", "assets/ic-download.svg", "Download Speaker"),
                               ("guide.html", "assets/ic-help.svg", "View the setup guide"),
                               ("download.html#releases", "assets/ic-doc.svg", "Read the release notes"),
                               ("mailto:[CONTACT EMAIL]", "assets/ic-mail.svg", "Contact support")))

# ---------------------------------------------------------------- index
index_body = f'''      <div class="welcome">
        <div class="welcome-copy">
          <h2>Welcome to Speaker</h2>
          <p>Speaker puts a microphone over every app on your Android phone. Tap it, speak naturally, and your words are typed right where you're writing. No keyboard switching, no copy and paste.</p>
          <div class="actions">
            <a class="go-btn" href="download.html"><img src="assets/ic-download.svg" alt=""><span>Download Speaker<small>Free &middot; Version 1.0.0 &middot; 137 KB</small></span></a>
            <a class="plain" href="guide.html">How do I set it up?</a>
          </div>
        </div>
        <div class="phone" aria-hidden="true"><div class="screen">
          <div class="appbar">Alex</div>
          <div class="msg">Are we still on for tomorrow's review?</div>
          <div class="msg me">Yes, ten o'clock in the main room.</div>
          <div class="msg">Great. Can you bring the printed copies?</div>
          <div class="mic">{MIC}</div>
          <div class="compose">Sure, I'll bring them along<span class="caret"></span></div>
        </div></div>
      </div>

      <div class="group" id="features">What Speaker does</div>
      <div class="tiles">
        <div class="tile"><img src="assets/logo.svg" alt=""><div><b>Floating microphone</b><span>A bubble floats over every app. Tap to start, tap again to stop, drag it anywhere.</span></div></div>
        <div class="tile"><img src="assets/ic-doc.svg" alt=""><div><b>Types where your cursor is</b><span>Words go straight into the text box you're using. If an app won't accept typing, they go to the clipboard.</span></div></div>
        <div class="tile"><img src="assets/ic-spark.svg" alt=""><div><b>AI rewrite (optional)</b><span>Your words collect in a draft card. Edit them, tap Send, and the clean version is typed in.</span></div></div>
        <div class="tile"><img src="assets/ic-gear.svg" alt=""><div><b>Your choice of engine</b><span>Free built-in recognition, or OpenAI, Groq, Gemini or Deepgram with your own key.</span></div></div>
        <div class="tile"><img src="assets/ic-phone.svg" alt=""><div><b>Many ways to start</b><span>The bubble, the notification button, the Quick Settings tile or the button in the app.</span></div></div>
        <div class="tile"><img src="assets/ic-shield.svg" alt=""><div><b>Private by design</b><span>No account, no ads, no analytics. We never receive your voice, text or keys.</span></div></div>
      </div>

      <div class="group">How it works</div>
      <ol class="steps">
        <li><b>Install Speaker</b> and follow the short setup card: microphone, the floating bubble and auto-typing.</li>
        <li><b>Open any app</b> and tap into a text box.</li>
        <li><b>Tap the bubble and talk.</b> Each phrase is typed as you pause. Tap again to stop.</li>
      </ol>
      <div class="balloon"><b class="head"><img src="assets/ic-info.svg" alt="">Tip: AI rewrite</b>With AI rewrite on, nothing is typed while you talk. Your words collect in a small card beside the bubble, where you can fix them. Tap Send (or the bubble) and the whole text is rewritten at once, then typed in. If no text box is selected, it's copied to the clipboard.</div>

      <div class="group">Speech engines</div>
      <table class="details">
        <tr><th style="width:30%">Name</th><th style="width:18%">Key</th><th>Description</th></tr>
        <tr><td><b>Android built-in</b></td><td>Not needed</td><td>Free. Uses your phone's own speech recognition.</td></tr>
        <tr><td>Groq</td><td>Your own</td><td>Very fast Whisper transcription, with a free tier.</td></tr>
        <tr><td>OpenAI</td><td>Your own</td><td>High accuracy across accents and technical words.</td></tr>
        <tr><td>Google Gemini</td><td>Your own</td><td>Multilingual dictation with Gemini models.</td></tr>
        <tr><td>Deepgram</td><td>Your own</td><td>Fast, accurate Nova speech models.</td></tr>
        <tr><td>Custom</td><td>Your own</td><td>Any OpenAI-compatible server, including your own.</td></tr>
      </table>

      <div class="group">Your words stay yours</div>
      <p>Speaker has no account, no servers, no ads and no analytics. Your voice goes either to your phone's own speech service or straight to the provider you chose. API keys stay in the app's private storage on your phone. <a href="privacy.html">Read the privacy policy</a>.</p>
'''

# ---------------------------------------------------------------- download
dl_body = '''      <h2 class="big">Download Speaker</h2>
      <p class="muted">Open this page on your Android phone to download and install Speaker directly.</p>

      <div class="dialog">
        <div class="titlebar"><img src="assets/logo.svg" alt=""><span class="t">Speaker Setup</span><div class="ctl" aria-hidden="true"><span class="close"></span></div></div>
        <div class="dialog-face">
          <div class="wiz-side">
            <div class="wiz-art"><img src="assets/logo.svg" alt=""></div>
            <div class="wiz-main">
              <h3>Welcome to the Speaker Setup</h3>
              <p>This will install Speaker 1.0.0 on your Android phone.</p>
              <p>Speaker isn't on Google Play yet, so Android asks you to confirm a couple of things the first time:</p>
              <ol class="steps">
                <li><b>Download the file.</b> If your browser asks whether to keep it, choose <i>Download anyway</i>.</li>
                <li><b>Open it</b> from the download notification or your Downloads folder.</li>
                <li><b>Allow installs from your browser.</b> Tap <i>Settings</i>, switch on <i>Allow from this source</i>, go back.</li>
                <li><b>Tap Install.</b> If Play Protect asks, tap <i>More details</i>, then <i>Install anyway</i>.</li>
                <li><b>Open Speaker</b> and follow the <a href="guide.html">Setup Guide</a>.</li>
              </ol>
              <p>Click Download to continue.</p>
            </div>
          </div>
          <div class="wiz-foot btnrow">
            <a class="btn" aria-disabled="true">&lt; Back</a>
            <a class="btn default" href="downloads/Speaker-1.0.0.apk" download>Download &gt;</a>
            <a class="btn" href="guide.html">Setup Guide</a>
          </div>
        </div>
      </div>

      <div class="balloon"><b class="head"><img src="assets/ic-warn.svg" alt="">Android 13 and later</b>The <i>Speaker auto-type</i> switch in Accessibility starts out restricted for apps installed from a website. The <a href="guide.html#restricted">Setup Guide</a> shows the one-time fix.</div>

      <div class="group">Updating</div>
      <p>Download the newer version from this page and install it over the one you have. Don't uninstall first: your settings, keys and permissions are kept. After updating, open Speaker and tap <b>Start floating bubble</b> again.</p>

      <div class="group" id="requirements">System requirements</div>
      <table class="details">
        <tr><th style="width:30%">Item</th><th>Requirement</th></tr>
        <tr><td>Operating system</td><td>Android 8.0 (Oreo) or later</td></tr>
        <tr><td>Microphone</td><td>Built-in or headset microphone</td></tr>
        <tr><td>Storage</td><td>Less than 1 MB</td></tr>
        <tr><td>Internet</td><td>Only for online speech engines and AI rewrite</td></tr>
        <tr><td>API key</td><td>Optional. Only for OpenAI, Groq, Gemini, Deepgram, Anthropic or a custom server</td></tr>
      </table>

      <div class="group" id="releases">Release notes</div>
      <fieldset class="gb"><legend>Speaker 1.0.0 &mdash; 22 November 2026</legend>
        <ul>
          <li>AI rewrite uses a draft card: your words collect beside the bubble, you can edit them, and the whole text is rewritten at once when you tap Send.</li>
          <li>Long-press the bubble for a quick menu to switch AI rewrite on or off, or open the app.</li>
          <li>When no text box is selected, the result is copied to the clipboard.</li>
          <li>Recent dictations: optionally keep your last 50 dictations and their AI versions on the phone.</li>
          <li>Model lists load from each provider and show only the models your key can use.</li>
        </ul>
      </fieldset>

      <div class="group" id="verify">File properties</div>
      <div class="dialog">
        <div class="titlebar"><img src="assets/ic-doc.svg" alt=""><span class="t">Speaker-1.0.0.apk Properties</span><div class="ctl" aria-hidden="true"><span class="close"></span></div></div>
        <div class="dialog-face">
          <ul class="tabs"><li class="on">General</li><li>Digital Signatures</li></ul>
          <div class="tab-panel">
            <div class="prop-head"><img src="assets/logo.svg" alt=""><input value="Speaker-1.0.0.apk" readonly aria-label="File name"></div>
            <div class="prop">
              <span class="k">Type of file:</span><span class="v">Android application package (.apk)</span>
              <span class="k">Package:</span><span class="v mono">app.speaker.dictate</span>
              <span class="k">Size:</span><span class="v">137 KB (139,705 bytes)</span>
              <span class="k">SHA-256:</span><span class="v mono">22a261374962c98a0b379009b99fd8251b719834489b26414cbbb6b428ff7374</span>
              <span class="k">Signer:</span><span class="v">CN=Speaker Upload</span>
              <span class="k">Certificate:</span><span class="v mono">52:5B:2F:12:A2:2F:F3:5B:D2:0E:71:F5:61:83:73:1B:EF:B8:4A:D0:28:DC:85:5D:95:39:55:66:2F:FA:D5:92</span>
            </div>
          </div>
          <div class="wiz-foot btnrow" style="border-top:0;box-shadow:none;padding-top:0"><a class="btn default" href="downloads/Speaker-1.0.0.apk" download>OK</a></div>
        </div>
      </div>
      <p class="muted">Optional: compare these values to make sure your download is genuine and unchanged.</p>
'''

# ---------------------------------------------------------------- guide
def table(head, rows, widths=None):
    th = "".join(f'<th{f" style=\"width:{w}\"" if w else ""}>{h}</th>' for h, w in zip(head, widths or [None]*len(head)))
    tr = "".join("<tr>" + "".join(f"<td>{c}</td>" for c in r) + "</tr>" for r in rows)
    return f'<table class="details"><tr>{th}</tr>{tr}</table>'

guide_body = f'''      <h2 class="big">Speaker Setup Guide</h2>
      <p class="muted">Everything you need to get Speaker running, from the first launch to AI rewrite.</p>

      <div class="group" id="permissions">Permissions</div>
      <p>When you first open Speaker, the <b>Finish setup</b> card lists four steps. Tap each one in turn.</p>
      {table(["Step", "What to do", "Why"], [
        ["<b>Microphone</b>", "Tap <i>Allow</i> and choose <i>While using the app</i>.", "To hear you while you dictate."],
        ["<b>Display over apps</b>", "Tap <i>Allow</i>, find Speaker in the list and switch it on.", "Shows the floating bubble."],
        ["<b>Auto-typing</b>", "Tap <i>Enable</i>, read the explanation, tap <i>Agree &amp; continue</i>, then open <i>Speaker auto-type</i> and switch it on.", "Types your words into the text box."],
        ["<b>Run in background</b>", "Tap <i>Allow</i> and choose <i>Unrestricted</i> or <i>Don't optimise</i>.", "Stops the phone closing Speaker."]], ["24%", None, "28%"])}
      <p>Auto-typing is optional. Without it, Speaker copies what you said to the clipboard. In Accessibility settings, <i>Speaker auto-type</i> may be listed under <i>Installed apps</i> or <i>Downloaded apps</i>.</p>

      <div class="group" id="restricted">Restricted settings (Android 13 and later)</div>
      <p>Android protects Accessibility for apps installed from outside Google Play. If the <i>Speaker auto-type</i> switch is greyed out:</p>
      <ol class="steps">
        <li>Try to switch it on once so Android shows the message, then close it.</li>
        <li>Open <b>Settings &gt; Apps &gt; Speaker</b>.</li>
        <li>Tap the <b>&#8942;</b> menu and choose <b>Allow restricted settings</b>. Confirm with your PIN or fingerprint.</li>
        <li>Go back to <b>Settings &gt; Accessibility &gt; Speaker auto-type</b> and switch it on.</li>
      </ol>

      <div class="group" id="battery">Background and battery</div>
      <p>Some phones close apps in the background to save battery. When that happens, the bubble disappears and Android also switches auto-typing off.</p>
      <div class="balloon"><b class="head"><img src="assets/ic-warn.svg" alt="">Realme, Oppo, OnePlus, Xiaomi, Vivo and Samsung</b>Set battery use to <i>Unrestricted</i>, turn on <i>Auto launch</i> or <i>Autostart</i> for Speaker if your phone has it, and lock Speaker in the recent-apps screen.</div>

      <div class="group" id="engine">Speech engines</div>
      {table(["Engine", "Key", "Notes"], [
        ["<b>Android built-in</b>", "Not needed", "Free. Uses your phone's own speech recognition."],
        ["Groq", "Required", "Fast Whisper models. Free tier available."],
        ["OpenAI", "Required", "Accurate transcription models."],
        ["Google Gemini", "Required", "Gemini models."],
        ["Deepgram", "Required", "Nova models."],
        ["Custom", "Required", "Any OpenAI-compatible server. Enter its address."]], ["26%", "16%", None])}
      <p>For an online engine, create an API key on the provider's website and paste it under <b>API keys</b>. Tap <b>Refresh</b> beside <i>Model</i> to load the models your key can use. Leave <i>Language</i> blank to detect it automatically. Providers charge for usage under their own terms; Speaker itself is free.</p>

      <div class="group" id="bubble">The floating bubble</div>
      <p>Tap <b>Start floating bubble</b>. The bubble appears at the edge of the screen and a Speaker notification shows while it runs.</p>
      {table(["Action", "Result"], [["Tap", "Start or stop dictation"], ["Drag", "Move the bubble"], ["Long-press", "Quick menu: switch AI rewrite on or off, or open Speaker"]], ["26%", None])}
      <p>You can also use the <b>Dictate</b> button in the notification, or add the <b>Dictate</b> tile to Quick Settings.</p>

      <div class="group" id="ai">AI rewrite</div>
      <ol class="steps">
        <li>In <b>AI rewrite</b>, switch it on, pick a provider (OpenAI, Anthropic Claude, Google Gemini, Groq or custom) and model, and add the key under <b>API keys</b>.</li>
        <li>Tap the bubble and speak. Your words collect in a <b>draft card</b> beside the bubble.</li>
        <li>Tap the text in the card to fix anything with the keyboard.</li>
        <li>Tap <b>Send</b>, or the bubble. The bubble shows <i>Processing</i>, then the rewritten text is typed in, or copied to the clipboard if no text box is selected.</li>
      </ol>
      {table(["Draft card", "Result"], [["&#10005;", "Discard the draft"], ["More", "Keep talking and add to the draft"], ["Send", "Rewrite the whole draft. If it fails, the draft is kept and the button changes to <i>Retry</i>."]], ["26%", None])}

      <div class="group" id="history">Recent dictations</div>
      <p>With AI rewrite on, switch on <b>Keep recent dictations on this phone</b> to keep your last 50 dictations with their AI versions, ready to copy. They're stored only on your phone, and switching the setting off clears them.</p>
'''

# ---------------------------------------------------------------- faq
def q(question, answer, open_=False):
    return f'      <details class="q"{" open" if open_ else ""}><summary>{question}</summary><div class="a"><p>{answer}</p></div></details>'

faq_body = "\n".join([
    '      <h2 class="big">Frequently Asked Questions</h2>',
    '      <p class="muted">Click a question to see the answer.</p>',
    '      <div class="group" id="installing">Installing</div>',
    q("Why isn't Speaker on Google Play?", "It's on its way. Until then you can install it from this website, and this page will link to the Play listing once it's live.", True),
    q("Android warns me about installing the file. Is that expected?", 'Yes. Android shows that warning for any app installed from outside Google Play. Speaker contains no ads or trackers and only asks for the permissions it needs. To check the file, compare it with the values on the <a href="download.html#verify">download page</a>.'),
    q("How do I update?", 'Download the newer version from the <a href="download.html">download page</a> and install it over the one you have. Don\'t uninstall first, so your settings and keys are kept.'),
    q("Can I switch to the Google Play version later?", "Yes. If Android won't install the Play version over this one, uninstall Speaker and then install it from Play. You'll need to enter your keys and settings again."),
    '      <div class="group" id="using">Using Speaker</div>',
    q("My words go to the clipboard instead of the text box.", "Auto-typing is switched off. Open <b>Settings &gt; Accessibility &gt; Speaker auto-type</b> and switch it back on. If it keeps turning itself off, see the next question.", True),
    q("The bubble disappears, or auto-typing keeps switching off.", 'Your phone is closing Speaker in the background to save battery, and Android switches off its Accessibility service when that happens. Set battery use to <i>Unrestricted</i>, turn on <i>Auto launch</i> or <i>Autostart</i> if your phone has it, and lock Speaker in Recents. See <a href="guide.html#battery">Background and battery</a>.'),
    q("The auto-type switch is greyed out.", 'That\'s Android 13\'s protection for apps installed outside Google Play. Go to <b>Settings &gt; Apps &gt; Speaker &gt; &#8942; &gt; Allow restricted settings</b>, then try again. See <a href="guide.html#restricted">Restricted settings</a>.'),
    q("Auto-typing doesn't work in one particular app.", "A few apps, such as some terminals and games, don't use standard text boxes. In those apps Speaker copies your words to the clipboard. Long-press and choose <i>Paste</i>."),
    q("Does dictation stop when I pause?", "No. Speaker keeps listening until you tap the bubble again, so you can stop to think."),
    q("Which languages are supported?", "That depends on the engine. Most online engines handle dozens of languages and detect them automatically, or you can set a code such as <i>en</i>, <i>hi</i> or <i>en-IN</i>. The built-in engine supports the languages installed for your phone's speech recognition."),
    '      <div class="group" id="costs">Costs and privacy</div>',
    q("Is Speaker free?", "Yes. There are no ads, subscriptions or in-app purchases. The built-in engine costs nothing. If you use an online engine or AI rewrite, you use your own key and pay that provider directly for what you use. Some providers offer free tiers."),
    q("Can you see what I say?", 'No. Speaker has no servers. Your voice goes either to your phone\'s own speech service or straight from your phone to the provider you chose. See the <a href="privacy.html">privacy policy</a>.'),
    q("Why does Speaker use Accessibility?", "Accessibility is the Android feature that lets an app type into text boxes in other apps, as a keyboard does. Speaker uses it only to type your dictated words. It doesn't read or collect your screen content."),
    q("Where are my API keys stored?", "Only in Speaker's private storage on your phone. They're excluded from backups and device transfers, and each key is sent only to the provider it belongs to."),
    '      <div class="group" id="contact">Contact support</div>',
    '      <p>Email <a href="mailto:[CONTACT EMAIL]">[CONTACT EMAIL]</a> with your phone model and Android version. In Speaker, <b>Logs &amp; debugging &gt; Copy</b> gives you a diagnostic log to paste into your message. It never contains your dictated words or keys.</p>',
])

# ---------------------------------------------------------------- privacy (reuse policy text from docs/privacy-policy.html)
src = (ROOT / "docs" / "privacy-policy.html").read_text(encoding="utf-8")
body_html = re.search(r"<body>(.*)</body>", src, re.S).group(1)
body_html = re.sub(r"<h1>.*?</h1>\s*", "", body_html, flags=re.S)
body_html = body_html.replace('<p class="muted">', '<p class="muted">')
body_html = re.sub(r"<h2>(.*?)</h2>", r'<div class="group">\1</div>', body_html)
body_html = body_html.replace("<table>", '<table class="details">')
body_html = body_html.replace("<h2>Sharing", "<h2>Sharing")
site_note = '<div class="group">This website</div>\n<p>This website uses no cookies, analytics, advertising or third-party scripts. Like any website, our hosting provider may keep standard server logs (such as IP address, browser type and the pages or files requested) for security and to keep the site running.</p>\n\n<div class="group">Sharing and selling</div>'
body_html = body_html.replace('<div class="group">Sharing and selling</div>', site_note)
body_html = body_html.replace("posted at this address", "posted on this page")
privacy_body = '      <h2 class="big">Privacy Policy</h2>\n' + body_html.strip()

# ---------------------------------------------------------------- write pages
common_end = (DETAILS,)
pages = {
    "index.html": ("Speaker - Welcome", "Speaker - Voice Typing for Every Android App",
                   "Speaker puts a microphone over every app on your Android phone. Tap, speak, and your words are typed into the text box you're using. Free download.",
                   "Speaker &gt; Welcome", pane(TASKS, OTHER, DETAILS), index_body, "Done"),
    "download.html": ("Speaker - Download", "Download Speaker for Android",
                      "Download Speaker 1.0.0, the free voice typing app for Android 8.0 and later.",
                      "Speaker &gt; Download", pane(("File Tasks", links(("downloads/Speaker-1.0.0.apk", "assets/ic-download.svg", "Download this file"), ("#requirements", "assets/ic-phone.svg", "System requirements"), ("#releases", "assets/ic-doc.svg", "Release notes"), ("#verify", "assets/ic-key.svg", "Verify the file"))), OTHER, DETAILS),
                      dl_body, "1 object selected (137 KB)"),
    "guide.html": ("Speaker - Help and Support", "Speaker Setup Guide",
                   "Set up Speaker on your Android phone: permissions, auto-typing, speech engines, the floating bubble and AI rewrite.",
                   "Speaker &gt; Help and Support &gt; Setup Guide", pane(("Pick a Help topic", links(("#permissions", "assets/ic-key.svg", "Permissions"), ("#restricted", "assets/ic-warn.svg", "Restricted settings"), ("#battery", "assets/ic-gear.svg", "Background and battery"), ("#engine", "assets/ic-gear.svg", "Speech engines"), ("#bubble", "assets/logo.svg", "The floating bubble"), ("#ai", "assets/ic-spark.svg", "AI rewrite"), ("#history", "assets/ic-doc.svg", "Recent dictations"))), OTHER),
                   guide_body, "Done"),
    "faq.html": ("Speaker - FAQ", "Speaker FAQ",
                 "Answers to common questions about installing and using Speaker, the voice typing app for Android.",
                 "Speaker &gt; Help and Support &gt; FAQ", pane(("Topics", links(("#installing", "assets/ic-download.svg", "Installing"), ("#using", "assets/logo.svg", "Using Speaker"), ("#costs", "assets/ic-shield.svg", "Costs and privacy"), ("#contact", "assets/ic-mail.svg", "Contact support"))), OTHER),
                 faq_body, "Done"),
    "privacy.html": ("Speaker - Privacy Policy", "Speaker Privacy Policy",
                     "Privacy policy for Speaker, the voice typing app for Android.",
                     "Speaker &gt; Privacy Policy", pane(("In Short", '<ul><li>&#10004; No account</li><li>&#10004; No ads or analytics</li><li>&#10004; No servers of ours</li><li>&#10004; Keys stay on your phone</li><li>&#10004; You choose every provider</li></ul>'), OTHER),
                     privacy_body, "Done"),
}
for f, (title, doc_title, desc, path_text, p, body, status) in pages.items():
    (OUT / f).write_text(chrome(f, title, doc_title, desc, path_text, p, body, status), encoding="utf-8", newline="\n")
print("ok")
