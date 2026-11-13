package app.speaker.dictate

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** Settings + an in-app "try it" dictation button. */
class MainActivity : Activity(), DictationEngine.Listener {

    private lateinit var prefs: Prefs
    private lateinit var engine: DictationEngine

    private lateinit var btnTry: Button
    private lateinit var txtResult: EditText
    private lateinit var txtStatus: TextView
    private lateinit var btnBubble: Button
    private lateinit var chkAutoInsert: CheckBox
    private lateinit var chkStopOnBlur: CheckBox
    private lateinit var btnUpdate: Button
    private lateinit var spnStt: Spinner
    private lateinit var edtSttModel: EditText
    private lateinit var edtLanguage: EditText
    private lateinit var chkPolish: CheckBox
    private lateinit var spnLlm: Spinner
    private lateinit var edtLlmModel: EditText
    private lateinit var edtPrompt: EditText
    private lateinit var edtCustomUrl: EditText
    private lateinit var edtLogUrl: EditText
    private val keyFields = mutableMapOf<String, EditText>()

    private var shownStt = SttProvider.BUILTIN
    private var shownLlm = LlmProvider.OPENAI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i("Speaker.ui", "Activity onCreate (restored=${savedInstanceState != null})")
        setContentView(R.layout.activity_main)
        applySystemBarPadding()
        prefs = Prefs(this)
        engine = DictationEngine(this, this, "app")

        btnTry = findViewById(R.id.btnTry)
        txtResult = findViewById(R.id.txtResult)
        txtStatus = findViewById(R.id.txtStatus)
        btnBubble = findViewById(R.id.btnBubble)
        chkAutoInsert = findViewById(R.id.chkAutoInsert)
        spnStt = findViewById(R.id.spnStt)
        edtSttModel = findViewById(R.id.edtSttModel)
        edtLanguage = findViewById(R.id.edtLanguage)
        chkPolish = findViewById(R.id.chkPolish)
        spnLlm = findViewById(R.id.spnLlm)
        edtLlmModel = findViewById(R.id.edtLlmModel)
        edtPrompt = findViewById(R.id.edtPrompt)
        edtCustomUrl = findViewById(R.id.edtCustomUrl)
        edtLogUrl = findViewById(R.id.edtLogUrl)

        buildKeyFields()
        setupSpinners()
        loadSettings()

        btnTry.setOnClickListener {
            ui("Tap: in-app speak button (state=${engine.state})")
            if (!hasMic()) { requestPerms(); return@setOnClickListener }
            saveSettings()
            engine.toggle()
        }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            ui("Tap: Copy text (${txtResult.text.length} chars)")
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("Dictation", txtResult.text))
            toast("Copied")
        }
        findViewById<Button>(R.id.btnMic).setOnClickListener { ui("Tap: Allow microphone"); requestPerms() }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            ui("Tap: Allow display over other apps")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            ui("Tap: Enable auto-typing (opening Accessibility settings)")
            toast("Find “Speaker auto-type” (may be under Installed/Downloaded apps) and turn it on")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnBubble.setOnClickListener { ui("Tap: ${btnBubble.text}"); toggleBubble() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            ui("Tap: battery optimization settings (ignoring=${ignoringBattery()})")
            toast("Find Speaker and choose “Don't optimize” / Unrestricted")
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
        chkPolish.setOnCheckedChangeListener { _, on -> ui("Toggle: LLM polish = $on") }
        chkAutoInsert.setOnCheckedChangeListener { _, on -> ui("Toggle: auto-type = $on") }
        chkStopOnBlur = findViewById(R.id.chkStopOnBlur)
        chkStopOnBlur.isChecked = prefs.stopOnFocusLoss
        chkStopOnBlur.setOnCheckedChangeListener { _, on -> ui("Toggle: stop when leaving text field = $on"); prefs.stopOnFocusLoss = on }
        btnUpdate = findViewById(R.id.btnUpdate)
        btnUpdate.text = "⬆ Update app (installed v${BuildConfig.VERSION_NAME})"
        btnUpdate.setOnClickListener {
            ui("Tap: Check for update")
            saveSettings()
            btnUpdate.isEnabled = false
            Updater.checkAndInstall(this) { msg ->
                btnUpdate.isEnabled = true
                toast(msg)
            }
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { ui("Tap: Save settings"); saveSettings(); toast("Saved") }
        findViewById<Button>(R.id.btnTestLog).setOnClickListener {
            ui("Tap: Test log URL")
            saveSettings()
            val url = prefs.logUrl
            if (url.isEmpty()) { toast("Enter your ngrok URL first (or use Copy logs)"); return@setOnClickListener }
            toast("Testing…")
            RemoteLog.test(url) { toast(it) }
        }
        findViewById<Button>(R.id.btnCopyLogs).setOnClickListener {
            ui("Tap: Copy logs")
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("Speaker logs", RemoteLog.recentText()))
            toast("Logs copied. Paste them anywhere (WhatsApp, email, chat)")
        }
        findViewById<Button>(R.id.btnShareLogs).setOnClickListener {
            ui("Tap: Share logs")
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Speaker logs")
                putExtra(Intent.EXTRA_TEXT, RemoteLog.recentText())
            }
            startActivity(Intent.createChooser(send, "Share logs"))
        }
    }

    override fun onResume() {
        super.onResume()
        ui("Settings screen opened (multi-window=${isInMultiWindowMode})")
        refreshStatus()
        // Quietly check the PC for a newer build and advertise it on the button.
        Updater.check(this) { info ->
            if (info != null && info.versionCode > BuildConfig.VERSION_CODE) {
                btnUpdate.text = "⬆ Update available: v${info.versionName}. Tap to install"
            }
        }
    }

    override fun onPause() {
        saveSettings()
        ui("Settings screen left. Saved: " + settingsSummary())
        super.onPause()
    }

    private fun ui(msg: String) = android.util.Log.i("Speaker.ui", msg)

    /**
     * Android 15+ draws apps edge-to-edge (behind the status bar, navigation bar and keyboard).
     * Pad the root layout by those areas so nothing is hidden and every control can be reached.
     */
    private fun applySystemBarPadding() {
        if (Build.VERSION.SDK_INT < 35) return // older Android isn't edge-to-edge; the system already insets us
        val root = findViewById<View>(R.id.root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val (l, t, r, b) = if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
                listOf(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            } else {
                @Suppress("DEPRECATION")
                listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            v.setPadding(l, t, r, b)
            ui("Window insets applied: left=$l top=$t right=$r bottom=$b")
            insets
        }
        root.requestApplyInsets()
    }

    /** Settings snapshot for logs. Says which keys are set, never the keys themselves. */
    private fun settingsSummary(): String {
        val stt = SttProvider.from(prefs.sttProvider)
        val llm = LlmProvider.from(prefs.llmProvider)
        val keys = KEY_VENDORS.map { it.first }.filter { prefs.apiKey(it).isNotEmpty() }
        return "stt=${stt.id}/${prefs.sttModel(stt)} lang=${prefs.language.ifBlank { "auto" }} " +
            "polish=${prefs.polishEnabled} llm=${llm.id}/${prefs.llmModel(llm)} autoType=${prefs.autoInsert} " +
            "keysSet=$keys logUrl=${if (prefs.logUrl.isEmpty()) "off" else "on"}"
    }

    override fun onStart() {
        super.onStart()
        ui("Activity onStart")
    }

    override fun onStop() {
        ui("Activity onStop (in-app dictation state=${engine.state}, bubble running=${BubbleService.isRunning})")
        if (engine.state != DictationEngine.State.IDLE) {
            android.util.Log.w("Speaker.ui", "In-app dictation is active while the screen is hidden: Android may " +
                "silence the mic. Use the floating bubble to dictate into other apps.")
        }
        super.onStop()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration?) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        ui("Multi-window mode changed: $isInMultiWindowMode")
    }

    override fun onDestroy() {
        ui("Activity onDestroy (isFinishing=$isFinishing, in-app dictation state=${engine.state}); releasing in-app engine")
        engine.release()
        super.onDestroy()
    }

    // ---- Settings UI ----

    private fun buildKeyFields() {
        val container = findViewById<LinearLayout>(R.id.keysContainer)
        KEY_VENDORS.forEach { (vendor, label) ->
            val field = EditText(this).apply {
                hint = label
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSingleLine = true
                // Keep password managers from offering to save/fill API keys.
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            container.addView(field)
            keyFields[vendor] = field
        }
    }

    private fun setupSpinners() {
        spnStt.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, SttProvider.entries.map { it.label }
        )
        spnLlm.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, LlmProvider.entries.map { it.label }
        )
        spnStt.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.setSttModel(shownStt, edtSttModel.text.toString())
                if (SttProvider.entries[pos] != shownStt) ui("Select: speech engine = ${SttProvider.entries[pos].id}")
                shownStt = SttProvider.entries[pos]
                prefs.sttProvider = shownStt.id
                edtSttModel.setText(prefs.sttModel(shownStt))
                edtSttModel.visibility = if (shownStt == SttProvider.BUILTIN) View.GONE else View.VISIBLE
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        spnLlm.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.setLlmModel(shownLlm, edtLlmModel.text.toString())
                if (LlmProvider.entries[pos] != shownLlm) ui("Select: polish LLM = ${LlmProvider.entries[pos].id}")
                shownLlm = LlmProvider.entries[pos]
                prefs.llmProvider = shownLlm.id
                edtLlmModel.setText(prefs.llmModel(shownLlm))
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    private fun loadSettings() {
        shownStt = SttProvider.from(prefs.sttProvider)
        shownLlm = LlmProvider.from(prefs.llmProvider)
        edtSttModel.setText(prefs.sttModel(shownStt))
        edtLlmModel.setText(prefs.llmModel(shownLlm))
        spnStt.setSelection(shownStt.ordinal)
        spnLlm.setSelection(shownLlm.ordinal)
        edtLanguage.setText(prefs.language)
        chkPolish.isChecked = prefs.polishEnabled
        chkAutoInsert.isChecked = prefs.autoInsert
        edtPrompt.setText(prefs.polishPrompt)
        edtCustomUrl.setText(prefs.customBaseUrl)
        keyFields.forEach { (vendor, field) -> field.setText(prefs.apiKey(vendor)) }
        edtLogUrl.setText(prefs.logUrlField)
    }

    private fun saveSettings() {
        prefs.sttProvider = shownStt.id
        prefs.setSttModel(shownStt, edtSttModel.text.toString())
        prefs.llmProvider = shownLlm.id
        prefs.setLlmModel(shownLlm, edtLlmModel.text.toString())
        prefs.language = edtLanguage.text.toString()
        prefs.polishEnabled = chkPolish.isChecked
        prefs.autoInsert = chkAutoInsert.isChecked
        prefs.polishPrompt = edtPrompt.text.toString()
        prefs.customBaseUrl = edtCustomUrl.text.toString()
        keyFields.forEach { (vendor, field) -> prefs.setApiKey(vendor, field.text.toString()) }
        prefs.logUrl = edtLogUrl.text.toString()
        RemoteLog.setUrl(prefs.logUrl)
    }

    // ---- Permissions & bubble ----

    private fun ignoringBattery() =
        getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ui("Permission result: " + permissions.indices.joinToString {
            "${permissions[it].substringAfterLast('.')}=${if (grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED) "granted" else "denied"}"
        })
        refreshStatus()
    }

    private fun toggleBubble() {
        if (BubbleService.isRunning) {
            stopService(Intent(this, BubbleService::class.java))
            btnBubble.postDelayed({ refreshStatus() }, 300)
            return
        }
        if (!hasMic()) { toast("Allow the microphone first"); requestPerms(); return }
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow “display over other apps” for the floating bubble")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        saveSettings()
        startForegroundService(Intent(this, BubbleService::class.java))
        btnBubble.postDelayed({ refreshStatus() }, 300)
    }

    private fun refreshStatus() {
        fun line(ok: Boolean, text: String) = (if (ok) "✅ " else "⬜ ") + text
        txtStatus.text = listOf(
            line(hasMic(), "Microphone"),
            line(Settings.canDrawOverlays(this), "Display over other apps (bubble)"),
            line(TypeAccessibilityService.isEnabled(this), "Auto-typing (optional; otherwise text is copied)"),
            line(ignoringBattery(), "Battery: not restricted (stops the phone killing the bubble)"),
            line(BubbleService.isRunning, "Bubble running"),
        ).joinToString("\n")
        btnBubble.text = if (BubbleService.isRunning) "Stop floating bubble" else "Start floating bubble"
        ui("Status: mic=${hasMic()} overlay=${Settings.canDrawOverlays(this)} " +
            "autoType=${TypeAccessibilityService.isEnabled(this)} a11yConnected=${TypeAccessibilityService.instance != null} " +
            "batteryUnrestricted=${ignoringBattery()} bubble=${BubbleService.isRunning}")
    }

    // ---- In-app dictation ----

    /** Text in the box before the current phrase; live words are shown after it. */
    private var resultBase = ""

    override fun onStateChanged(state: DictationEngine.State) {
        if (state == DictationEngine.State.RECORDING) {
            resultBase = txtResult.text.toString().let { if (it.isNotEmpty() && !it.last().isWhitespace()) "$it " else it }
        }
        btnTry.text = when (state) {
            DictationEngine.State.IDLE -> "🎤  Tap to speak"
            DictationEngine.State.RECORDING -> "⏹  Listening (live). Tap to stop"
            DictationEngine.State.WORKING -> "…  Finishing last words"
        }
        btnTry.isEnabled = state != DictationEngine.State.WORKING
    }

    override fun onText(text: String, isFinal: Boolean, sessionText: String) {
        val shown = resultBase + text.trimStart()
        txtResult.setText(shown)
        txtResult.setSelection(shown.length)
        if (isFinal) {
            resultBase = shown
            ui("In-app phrase settled: ${text.length} chars")
        }
    }

    override fun onError(message: String) {
        android.util.Log.w("Speaker.ui", "Error shown in app: $message")
        toast(message)
    }

    private fun toast(msg: String) {
        ui("Toast: $msg")
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
