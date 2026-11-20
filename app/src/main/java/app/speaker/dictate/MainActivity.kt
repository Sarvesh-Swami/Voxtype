package app.speaker.dictate

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Main screen: dictate in-app, set up permissions, run the floating bubble, configure engines and keys. */
class MainActivity : Activity(), DictationEngine.Listener {

    private lateinit var prefs: Prefs
    private lateinit var engine: DictationEngine

    private lateinit var btnTry: ImageButton
    private lateinit var txtTryStatus: TextView
    private lateinit var txtTryHint: TextView
    private lateinit var txtResult: EditText
    private lateinit var btnUpdate: Button
    private lateinit var btnBubble: Button
    private lateinit var txtBubbleState: TextView
    private lateinit var cardSetup: View
    private lateinit var setupRows: LinearLayout
    private lateinit var txtSetupProgress: TextView
    private lateinit var swAutoType: Switch
    private lateinit var swStopOnBlur: Switch
    private lateinit var swIsland: Switch
    private lateinit var swShowBubble: Switch
    private lateinit var swPolish: Switch
    private lateinit var polishOptions: View
    private lateinit var spnStt: Spinner
    private lateinit var rowSttModel: View
    private lateinit var edtSttModel: EditText
    private lateinit var edtLanguage: EditText
    private lateinit var spnLlm: Spinner
    private lateinit var edtLlmModel: EditText
    private lateinit var edtPrompt: EditText
    private lateinit var edtCustomUrl: EditText
    private lateinit var edtLogUrl: EditText
    private val keyFields = mutableMapOf<String, EditText>()
    private lateinit var sttPicker: ModelPicker
    private lateinit var llmPicker: ModelPicker

    private var shownStt = SttProvider.BUILTIN
    private var shownLlm = LlmProvider.OPENAI
    private var micPulse: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui("Activity onCreate (restored=${savedInstanceState != null})")
        setContentView(R.layout.activity_main)
        applySystemBarPadding()
        prefs = Prefs(this)
        engine = DictationEngine(this, this, "app")

        btnTry = findViewById(R.id.btnTry)
        txtTryStatus = findViewById(R.id.txtTryStatus)
        txtTryHint = findViewById(R.id.txtTryHint)
        txtResult = findViewById(R.id.txtResult)
        btnUpdate = findViewById(R.id.btnUpdate)
        btnBubble = findViewById(R.id.btnBubble)
        txtBubbleState = findViewById(R.id.txtBubbleState)
        cardSetup = findViewById(R.id.cardSetup)
        setupRows = findViewById(R.id.setupRows)
        txtSetupProgress = findViewById(R.id.txtSetupProgress)
        swAutoType = findViewById(R.id.swAutoType)
        swStopOnBlur = findViewById(R.id.swStopOnBlur)
        swIsland = findViewById(R.id.swIsland)
        swShowBubble = findViewById(R.id.swShowBubble)
        swPolish = findViewById(R.id.swPolish)
        polishOptions = findViewById(R.id.polishOptions)
        spnStt = findViewById(R.id.spnStt)
        rowSttModel = findViewById(R.id.rowSttModel)
        edtSttModel = findViewById(R.id.edtSttModel)
        edtLanguage = findViewById(R.id.edtLanguage)
        spnLlm = findViewById(R.id.spnLlm)
        edtLlmModel = findViewById(R.id.edtLlmModel)
        edtPrompt = findViewById(R.id.edtPrompt)
        edtCustomUrl = findViewById(R.id.edtCustomUrl)
        edtLogUrl = findViewById(R.id.edtLogUrl)

        findViewById<TextView>(R.id.txtVersion).text = "v${BuildConfig.VERSION_NAME}"
        findViewById<TextView>(R.id.txtFooter).text =
            "Speaker v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · your keys stay on this phone"

        sttPicker = ModelPicker(
            R.id.spnSttModel, R.id.edtSttModel, R.id.btnSttModels,
            slot = { ModelCatalog.sttSlot(shownStt) },
            choices = { ModelCatalog.sttChoices(prefs, shownStt) },
            fetch = { ModelCatalog.fetchStt(prefs, shownStt) },
            missingKey = {
                val p = shownStt
                if (p == SttProvider.BUILTIN || p == SttProvider.CUSTOM || prefs.apiKey(p.vendor).isNotEmpty()) null
                else keyLabel(p.vendor)
            },
        )
        llmPicker = ModelPicker(
            R.id.spnLlmModel, R.id.edtLlmModel, R.id.btnLlmModels,
            slot = { ModelCatalog.llmSlot(shownLlm) },
            choices = { ModelCatalog.llmChoices(prefs, shownLlm) },
            fetch = { ModelCatalog.fetchLlm(prefs, shownLlm) },
            missingKey = {
                val p = shownLlm
                if (p == LlmProvider.CUSTOM || prefs.apiKey(p.vendor).isNotEmpty()) null else keyLabel(p.vendor)
            },
        )
        buildKeyFields()
        setupSpinners()
        loadSettings()
        wireActions()
        applyFlavor()
    }

    /** Play builds have no server: hide the sideload Update button and the remote-log URL. */
    private fun applyFlavor() {
        if (BuildConfig.DEV_TOOLS) return
        btnUpdate.visibility = View.GONE
        edtLogUrl.visibility = View.GONE
        findViewById<View>(R.id.lblLogUrl).visibility = View.GONE
        findViewById<View>(R.id.btnTestLog).visibility = View.GONE
        findViewById<TextView>(R.id.txtLogsBody).text =
            "Having a problem? Copy or share a diagnostic log with support. Nothing is sent automatically, " +
                "and the words you dictate are never written to the log."
    }

    private fun wireActions() {
        btnTry.setOnClickListener {
            ui("Tap: in-app mic (state=${engine.state})")
            if (!hasMic()) { requestPerms(); return@setOnClickListener }
            saveSettings()
            engine.toggle()
        }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            ui("Tap: Copy text (${txtResult.text.length} chars)")
            copy("Dictation", txtResult.text)
            toast("Copied")
        }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            ui("Tap: Clear text")
            txtResult.setText("")
            resultBase = ""
        }
        btnBubble.setOnClickListener { ui("Tap: ${btnBubble.text}"); toggleBubble() }
        btnUpdate.setOnClickListener {
            ui("Tap: Update")
            saveSettings()
            btnUpdate.isEnabled = false
            btnUpdate.text = "Checking…"
            Updater.checkAndInstall(this) { msg ->
                btnUpdate.isEnabled = true
                btnUpdate.text = "Update"
                toast(msg)
            }
        }
        swAutoType.setOnCheckedChangeListener { _, on -> ui("Toggle: auto-type = $on"); prefs.autoInsert = on }
        swStopOnBlur.setOnCheckedChangeListener { _, on -> ui("Toggle: stop when leaving text field = $on"); prefs.stopOnFocusLoss = on }
        swIsland.setOnCheckedChangeListener { _, on ->
            if (prefs.islandMedia == on) return@setOnCheckedChangeListener
            ui("Toggle: island/media controls = $on"); prefs.islandMedia = on; BubbleService.refresh(this)
        }
        swShowBubble.setOnCheckedChangeListener { _, on ->
            if (prefs.showBubble == on) return@setOnCheckedChangeListener
            ui("Toggle: show floating bubble = $on"); prefs.showBubble = on; BubbleService.refresh(this)
        }
        swPolish.setOnCheckedChangeListener { _, on ->
            polishOptions.visibility = if (on) View.VISIBLE else View.GONE
            findViewById<View>(R.id.btnEditPrompt).visibility = if (on) View.VISIBLE else View.GONE
            if (prefs.polishEnabled == on) return@setOnCheckedChangeListener // just loading settings
            ui("Toggle: AI rewrite = $on")
            if (on) showPromptDialog(enabling = true) else prefs.polishEnabled = false
        }
        findViewById<Switch>(R.id.swHistory).setOnCheckedChangeListener { _, on ->
            if (prefs.keepHistory == on) return@setOnCheckedChangeListener
            ui("Toggle: keep recent dictations = $on"); prefs.keepHistory = on
            if (!on) {
                RewriteHistory.clear(this)
                toast("Recent dictations cleared and turned off")
            }
        }
        findViewById<Button>(R.id.btnHistory).setOnClickListener { ui("Tap: Recent dictations"); showHistory() }
        findViewById<Button>(R.id.btnEditPrompt).setOnClickListener {
            ui("Tap: Edit session prompt")
            showPromptDialog(enabling = false)
        }
        findViewById<Button>(R.id.btnTestLog).setOnClickListener {
            ui("Tap: Test log URL")
            saveSettings()
            val url = prefs.logUrl
            if (url.isEmpty()) { toast("Enter your log URL first (or use Copy)"); return@setOnClickListener }
            toast("Testing…")
            RemoteLog.test(url) { toast(it) }
        }
        findViewById<View>(R.id.btnPrivacy).setOnClickListener { ui("Tap: Privacy"); showPrivacy() }
        findViewById<Button>(R.id.btnCopyLogs).setOnClickListener {
            ui("Tap: Copy logs")
            copy("Speaker logs", RemoteLog.recentText())
            toast("Logs copied. Paste them anywhere")
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
        ui("Settings screen opened (multi-window=$isInMultiWindowMode)")
        refreshStatus()
        if (BuildConfig.DEV_TOOLS) Updater.check(this) { info ->
            if (info != null && info.versionCode > BuildConfig.VERSION_CODE) {
                btnUpdate.text = "Update to v${info.versionName}"
                btnUpdate.setBackgroundResource(R.drawable.bg_pill_success)
            }
        }
    }

    override fun onPause() {
        saveSettings()
        ui("Settings screen left. Saved: " + settingsSummary())
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        ui("Activity onStart")
    }

    override fun onStop() {
        ui("Activity onStop (in-app dictation state=${engine.state}, bubble running=${BubbleService.isRunning})")
        // The in-app mic has no foreground service: Android blocks it in the background anyway, and an
        // app must not keep listening once the user has left it. The bubble is for other apps.
        engine.stopIfActive("app moved to the background")
        super.onStop()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration?) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        ui("Multi-window mode changed: $isInMultiWindowMode")
    }

    override fun onDestroy() {
        ui("Activity onDestroy (isFinishing=$isFinishing, in-app dictation state=${engine.state}); releasing in-app engine")
        micPulse?.cancel()
        engine.release()
        super.onDestroy()
    }

    private fun ui(msg: String) = android.util.Log.i("Speaker.ui", msg)

    private val promptPresets = listOf(
        "Fix grammar" to "Fix grammar, spelling and punctuation. Keep my words and tone; change as little as possible.",
        "Professional" to "Rewrite it as clear, polite, professional writing suitable for work chat or email.",
        "Casual" to "Rewrite it as friendly, casual chat. Fix mistakes, keep it short and natural.",
        "To English" to "Translate it into natural English (it may be Hindi, Hinglish or another language). Fix grammar.",
        "Concise" to "Make it shorter and clearer without losing meaning. Fix grammar.",
        "Commands" to "I am dictating a terminal command. Output only the shell command, in lowercase where appropriate, with correct symbols and no explanation.",
    )

    /**
     * Session prompt editor. Opened when AI rewrite is switched on (Cancel leaves it off) and from
     * "Edit session prompt". The prompt is sent to the LLM with every phrase you dictate.
     */
    private fun showPromptDialog(enabling: Boolean) {
        val field = EditText(this, null, 0, R.style.Field).apply {
            setText(prefs.polishPrompt)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            maxLines = 8
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            hint = "e.g. Fix my grammar and make it sound professional"
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        promptPresets.forEach { (label, text) ->
            chips.addView(Button(this, null, 0, R.style.ButtonTonal).apply {
                this.text = label
                isAllCaps = false
                setOnClickListener { ui("Prompt preset: $label"); field.setText(text); field.setSelection(field.text.length) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(8) })
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(TextView(this@MainActivity, null, 0, R.style.Body).apply {
                text = "Tell the AI what to do with everything you say. It's applied to every sentence before it's typed."
            })
            addView(android.widget.HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(field, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        var saved = false
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(if (enabling) "Turn on AI rewrite" else "Session prompt")
            .setView(android.widget.ScrollView(this).apply { addView(body) })
            .setPositiveButton(if (enabling) "Turn on" else "Save") { _, _ ->
                saved = true
                val p = field.text.toString().trim()
                prefs.polishPrompt = p // blank falls back to the default clean-up prompt
                edtPrompt.setText(prefs.polishPrompt)
                prefs.polishEnabled = true
                ui("Session prompt saved (${p.length} chars, AI rewrite on)")
                val llm = LlmProvider.from(prefs.llmProvider)
                if (prefs.apiKey(llm.vendor).isBlank()) {
                    toast("AI rewrite is on. Add your ${llm.label} API key in API keys below")
                } else toast("AI rewrite is on (${llm.label})")
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnDismissListener {
            if (enabling && !saved) {
                ui("Prompt dialog cancelled: AI rewrite stays off")
                swPolish.isChecked = false
            }
        }
        dialog.show()
    }

    // ---------------- layout / system bars ----------------

    /**
     * Android 15+ draws edge-to-edge. The gradient header extends under the status bar (padded by its
     * height); the rest of the layout is padded for the navigation bar, cutouts and keyboard.
     */
    private fun applySystemBarPadding() {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= 30 && !dark) {
            // Dark navigation-bar icons on our light background; status bar stays light-on-gradient.
            window.insetsController?.setSystemBarsAppearance(
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }
        if (Build.VERSION.SDK_INT < 35) return // older Android isn't edge-to-edge
        val root = findViewById<View>(R.id.root)
        val header = findViewById<View>(R.id.header)
        val headerTop = header.paddingTop
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
            v.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
            header.setPadding(header.paddingLeft, headerTop + bars.top, header.paddingRight, header.paddingBottom)
            ui("Window insets applied: top=${bars.top} bottom=${maxOf(bars.bottom, ime.bottom)} left=${bars.left} right=${bars.right}")
            insets
        }
        root.requestApplyInsets()
    }

    // ---------------- settings ----------------

    private fun settingsSummary(): String {
        val stt = SttProvider.from(prefs.sttProvider)
        val llm = LlmProvider.from(prefs.llmProvider)
        val keys = KEY_VENDORS.map { it.first }.filter { prefs.apiKey(it).isNotEmpty() }
        return "stt=${stt.id}/${prefs.sttModel(stt)} lang=${prefs.language.ifBlank { "auto" }} " +
            "polish=${prefs.polishEnabled} llm=${llm.id}/${prefs.llmModel(llm)} autoType=${prefs.autoInsert} " +
            "keysSet=$keys logUrl=${if (prefs.logUrl.isEmpty()) "off" else "on"}"
    }

    private fun buildKeyFields() {
        val container = findViewById<LinearLayout>(R.id.keysContainer)
        KEY_VENDORS.forEach { (vendor, label) ->
            val title = TextView(this, null, 0, R.style.Label).apply { text = label.uppercase() }
            val field = EditText(this, null, 0, R.style.Field).apply {
                hint = "Paste key"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSingleLine = true
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO // keep password managers away from API keys
            }
            container.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(6) })
            container.addView(field, LinearLayout.LayoutParams(-1, -2))
            keyFields[vendor] = field
        }
    }

    private fun listAdapter(items: List<String>) = ArrayAdapter(this, R.layout.spinner_item, items).apply {
        setDropDownViewResource(R.layout.spinner_dropdown_item)
    }

    /**
     * Model dropdown for one provider picker: built-in + fetched model names, plus "Other" which reveals
     * a text field. The text field always holds the chosen name, so saving reads it as before.
     */
    private inner class ModelPicker(
        spinnerId: Int, fieldId: Int, refreshId: Int,
        private val slot: () -> String,
        private val choices: () -> List<String>,
        private val fetch: () -> List<String>,
        /** Vendor label whose missing key blocks fetching, or null when fetching can go ahead. */
        private val missingKey: () -> String?,
    ) {
        val field: EditText = findViewById(fieldId)
        private val spinner: Spinner = findViewById(spinnerId)
        private var models = emptyList<String>()
        private var fetching = false

        init {
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (pos < models.size) {
                        if (field.text.toString() != models[pos]) ui("Select: model ${slot()} = ${models[pos]}")
                        field.setText(models[pos])
                        field.visibility = View.GONE
                    } else {
                        field.visibility = View.VISIBLE
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
            findViewById<View>(refreshId).setOnClickListener { ui("Tap: refresh models ${slot()}"); refresh(manual = true) }
        }

        /** Shows the list for the current provider; fetches the full list once if a key is set. */
        fun show() {
            bind()
            if (prefs.fetchedModels(slot()).isEmpty() && missingKey() == null) refresh(manual = false)
        }

        private fun bind() {
            val current = field.text.toString().trim()
            val available = prefs.fetchedModels(slot())
            if (available.isNotEmpty()) {
                // The provider told us what this key can use: offer exactly that, nothing else.
                models = choices()
                var index = models.indexOf(current)
                if (index < 0) {
                    ui("Model \"$current\" is not available for this key (${slot()}); switched to ${models.first()}")
                    if (current.isNotEmpty()) toast("\"$current\" isn't available for your key. Using ${models.first()}")
                    field.setText(models.first())
                    index = 0
                }
                spinner.adapter = listAdapter(models)
                spinner.setSelection(index, false)
                field.visibility = View.GONE
                return
            }
            // No list from the provider yet (no key, or it couldn't be loaded): suggestions + free typing.
            models = choices().let { if (current.isNotEmpty() && current !in it) listOf(current) + it else it }
            spinner.adapter = listAdapter(models + "Other (type a name)")
            val index = models.indexOf(current)
            spinner.setSelection(if (index >= 0) index else models.size, false)
            field.visibility = if (index >= 0) View.GONE else View.VISIBLE
        }

        private fun refresh(manual: Boolean) {
            if (fetching) return
            // The fetch reads keys and the custom URL from prefs. Only save on a tap: an automatic
            // fetch can run while the screen is still loading, before the fields hold the settings.
            if (manual) saveSettings()
            missingKey()?.let { vendor ->
                if (manual) toast("Add your $vendor in API keys below")
                return
            }
            val requested = slot()
            fetching = true
            Thread({
                val result = runCatching { fetch() }
                runOnUiThread {
                    fetching = false
                    if (isDestroyed || slot() != requested) return@runOnUiThread
                    result.onSuccess { list ->
                        ui("Models $requested: ${list.size} available for this key: ${list.joinToString()}")
                        bind()
                        if (manual) toast(if (list.isEmpty()) "The provider returned no models" else "${list.size} models loaded")
                    }.onFailure { e ->
                        ui("Models $requested: fetch failed: $e")
                        if (manual) toast("Couldn't load models: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }, "model-list").start()
        }
    }

    private fun keyLabel(vendor: String) = KEY_VENDORS.firstOrNull { it.first == vendor }?.second ?: vendor

    private fun setupSpinners() {
        spnStt.adapter = listAdapter(SttProvider.entries.map { it.label })
        spnLlm.adapter = listAdapter(LlmProvider.entries.map { it.label })
        spnStt.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.setSttModel(shownStt, edtSttModel.text.toString())
                if (SttProvider.entries[pos] != shownStt) ui("Select: speech engine = ${SttProvider.entries[pos].id}")
                shownStt = SttProvider.entries[pos]
                prefs.sttProvider = shownStt.id
                edtSttModel.setText(prefs.sttModel(shownStt))
                rowSttModel.visibility = if (shownStt == SttProvider.BUILTIN) View.GONE else View.VISIBLE
                if (shownStt != SttProvider.BUILTIN) sttPicker.show()
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
                llmPicker.show()
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
        rowSttModel.visibility = if (shownStt == SttProvider.BUILTIN) View.GONE else View.VISIBLE
        if (shownStt != SttProvider.BUILTIN) sttPicker.show()
        llmPicker.show()
        edtLanguage.setText(prefs.language)
        swPolish.isChecked = prefs.polishEnabled
        polishOptions.visibility = if (prefs.polishEnabled) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnEditPrompt).visibility = if (prefs.polishEnabled) View.VISIBLE else View.GONE
        swAutoType.isChecked = prefs.autoInsert
        swStopOnBlur.isChecked = prefs.stopOnFocusLoss
        swIsland.isChecked = prefs.islandMedia
        swShowBubble.isChecked = prefs.showBubble
        findViewById<Switch>(R.id.swHistory).isChecked = prefs.keepHistory
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
        prefs.polishEnabled = swPolish.isChecked
        prefs.autoInsert = swAutoType.isChecked
        prefs.stopOnFocusLoss = swStopOnBlur.isChecked
        prefs.polishPrompt = edtPrompt.text.toString()
        prefs.customBaseUrl = edtCustomUrl.text.toString()
        keyFields.forEach { (vendor, field) -> prefs.setApiKey(vendor, field.text.toString()) }
        prefs.logUrl = edtLogUrl.text.toString()
        RemoteLog.setUrl(prefs.logUrl)
    }

    // ---------------- permissions, setup checklist, bubble ----------------

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

    private data class SetupItem(val done: Boolean, val title: String, val subtitle: String, val action: String, val fix: () -> Unit)

    private fun setupItems() = listOf(
        SetupItem(hasMic(), "Microphone", "Needed to hear you", "Allow") {
            ui("Tap: setup Microphone"); requestPerms()
        },
        SetupItem(Settings.canDrawOverlays(this), "Display over apps", "Shows the floating bubble", "Allow") {
            ui("Tap: setup Display over other apps")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        },
        SetupItem(TypeAccessibilityService.isEnabled(this), "Auto-typing", "Types into any text field", "Enable") {
            ui("Tap: setup Auto-typing (showing accessibility disclosure)")
            showAccessibilityDisclosure()
        },
        SetupItem(ignoringBattery(), "Run in background", "Stops the phone closing the bubble", "Allow") {
            ui("Tap: setup battery optimization (ignoring=${ignoringBattery()})")
            toast("Choose Speaker → “Don't optimize” / Unrestricted")
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        },
    )

    /**
     * Google Play requires a prominent, in-app disclosure and the user's affirmative consent before
     * an app that isn't an accessibility tool sends them to turn on its AccessibilityService.
     */
    private fun showAccessibilityDisclosure() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Allow Speaker to type for you?")
            .setMessage(
                "Speaker uses Android's Accessibility service to type the words you dictate into the text " +
                    "field you're using in any app, the way a keyboard does.\n\n" +
                    "• It only types when you start dictation.\n" +
                    "• It does not read, collect, store or share your screen content or anything you type.\n" +
                    "• No data from this service leaves your phone.\n\n" +
                    "On the next screen, open “Speaker auto-type” and turn it on. You can turn it off any time " +
                    "in Settings → Accessibility."
            )
            .setPositiveButton("Agree & continue") { _, _ ->
                ui("Accessibility disclosure: agreed; opening Accessibility settings")
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    .onFailure { toast("Open Settings → Accessibility → Speaker auto-type") }
                toast(
                    if (BuildConfig.DEV_TOOLS) "Open “Speaker auto-type” and turn it on. Greyed out? App info → ⋮ → Allow restricted settings"
                    else "Open “Speaker auto-type” and turn it on"
                )
            }
            .setNegativeButton("Not now") { _, _ ->
                ui("Accessibility disclosure: declined")
                toast("Without auto-typing, dictation is copied to the clipboard so you can paste it")
            }
            .show()
    }

    /** Short in-app privacy summary (the full policy must also be linked in the Play listing). */
    /** "Recent dictations": what you said and what the AI wrote, newest first. Tap a card to copy. */
    private fun showHistory() {
        val entries = RewriteHistory.load(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        if (entries.isEmpty()) {
            list.addView(TextView(this, null, 0, R.style.Body).apply {
                text = if (prefs.keepHistory) "Nothing yet. Dictate with AI rewrite on and your sentences show up here."
                else "Recent dictations are turned off."
            })
        }
        val timeFmt = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        entries.forEach { e ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            item.addView(TextView(this, null, 0, R.style.Label).apply {
                text = timeFmt.format(java.util.Date(e.time)) + if (e.rewritten == null) "  ·  AI FAILED" else ""
            })
            item.addView(TextView(this, null, 0, R.style.Body).apply { text = "🎤 ${e.spoken}" })
            item.addView(TextView(this).apply {
                text = if (e.rewritten != null) "✨ ${e.rewritten}" else "⚠ ${e.note ?: "AI rewrite failed"}"
                textSize = 15f
                setTextColor(getColor(R.color.text_primary))
                setPadding(0, dp(4), 0, 0)
            })
            item.setOnClickListener {
                val copyText = e.rewritten ?: e.spoken
                copy("Dictation", copyText)
                ui("Recent dictations: copied an entry (${copyText.length} chars)")
                toast(if (e.rewritten != null) "AI version copied" else "Your words copied")
            }
            item.setOnLongClickListener {
                copy("Dictation", e.spoken)
                toast("Your original words copied")
                true
            }
            list.addView(item)
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Recent dictations")
            .setView(android.widget.ScrollView(this).apply { addView(list) })
            .setPositiveButton("Close", null)
        if (entries.isNotEmpty()) {
            dialog.setMessage("Tap to copy the AI version, long-press to copy what you said. Stored only on this phone.")
            dialog.setNeutralButton("Clear all") { _, _ ->
                RewriteHistory.clear(this)
                ui("Recent dictations cleared")
                toast("Recent dictations cleared")
            }
        }
        dialog.show()
    }

    private fun showPrivacy() {
        val policyUrl = getString(R.string.privacy_policy_url)
        val builder = android.app.AlertDialog.Builder(this)
            .setTitle("Privacy & permissions")
            .setMessage(
                "Speaker has no account, no servers and no analytics. The developer receives nothing.\n\n" +
                    "• Microphone: used only while you dictate. With the built-in engine, audio is processed by " +
                    "your phone's speech service (Android/Google). With an API engine, each phrase is sent " +
                    "straight from your phone to the provider you chose (OpenAI, Groq, Google Gemini, Deepgram " +
                    "or your custom URL), using your own key.\n" +
                    "• AI rewrite (optional): when you tap Send, your transcribed text and your prompt go to the AI " +
                    "provider you chose. If \"Keep recent dictations\" is on, your last 50 dictations and their AI versions are kept on " +
                    "this phone only (never sent or backed up) until you clear them.\n" +
                    "• API keys and settings stay on this phone (not backed up) and go only to that provider.\n" +
                    "• Accessibility: only types your dictation into the focused field; nothing is read or collected.\n" +
                    "• Display over other apps: shows the floating mic bubble.\n\n" +
                    "Providers handle your data under their own privacy policies."
            )
            .setPositiveButton("OK", null)
        if (policyUrl.startsWith("https://")) {
            builder.setNeutralButton("Full policy") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(policyUrl))) }
            }
        }
        builder.show()
    }

    private fun refreshStatus() {
        val items = setupItems()
        val done = items.count { it.done }
        txtSetupProgress.text = "$done of ${items.size}"
        cardSetup.visibility = if (done == items.size) View.GONE else View.VISIBLE
        setupRows.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (item in items) {
            val row = inflater.inflate(R.layout.row_setup, setupRows, false)
            row.findViewById<TextView>(R.id.title).text = item.title
            row.findViewById<TextView>(R.id.subtitle).text = if (item.done) "Done" else item.subtitle
            row.findViewById<ImageView>(R.id.icon).apply {
                setImageResource(if (item.done) R.drawable.ic_check_circle else R.drawable.ic_alert_circle)
                setBackgroundResource(if (item.done) R.drawable.bg_status_ok else R.drawable.bg_status_todo)
            }
            row.findViewById<Button>(R.id.action).apply {
                text = item.action
                visibility = if (item.done) View.GONE else View.VISIBLE
                setOnClickListener { item.fix() }
            }
            setupRows.addView(row)
        }

        val running = BubbleService.isRunning
        btnBubble.text = if (running) "Stop floating bubble" else "Start floating bubble"
        btnBubble.setBackgroundResource(if (running) R.drawable.bg_button_danger else R.drawable.bg_button_primary)
        txtBubbleState.text = if (running) "● On" else "Off"
        txtBubbleState.setTextColor(getColor(if (running) R.color.success else R.color.text_secondary))

        ui("Status: mic=${hasMic()} overlay=${Settings.canDrawOverlays(this)} " +
            "autoType=${TypeAccessibilityService.isEnabled(this)} a11yConnected=${TypeAccessibilityService.instance != null} " +
            "batteryUnrestricted=${ignoringBattery()} bubble=$running setupDone=$done/${items.size}")
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

    // ---------------- in-app dictation ----------------

    /** Text in the box before the current phrase; live words are shown after it. */
    private var resultBase = ""

    override fun onStateChanged(state: DictationEngine.State) {
        if (state == DictationEngine.State.RECORDING) {
            resultBase = txtResult.text.toString().let { if (it.isNotEmpty() && !it.last().isWhitespace()) "$it " else it }
            draftStart = if (engine.draftMode) resultBase.length else -1
        }
        when (state) {
            DictationEngine.State.IDLE -> {
                btnTry.setBackgroundResource(R.drawable.bg_mic_idle)
                btnTry.setImageResource(R.drawable.ic_mic)
                txtTryStatus.text = "Tap to dictate"
                txtTryHint.text = "Try it here, or use the floating bubble in any app"
            }
            DictationEngine.State.RECORDING -> {
                btnTry.setBackgroundResource(R.drawable.bg_mic_recording)
                btnTry.setImageResource(R.drawable.ic_stop)
                txtTryStatus.text = "Listening…"
                txtTryHint.text = if (engine.draftMode) "AI rewrite on: speak, then tap stop to rewrite it all"
                else "Speak naturally. Pauses are fine. Tap to stop"
            }
            DictationEngine.State.WORKING -> {
                btnTry.setBackgroundResource(R.drawable.bg_mic_working)
                btnTry.setImageResource(R.drawable.ic_dots)
                txtTryStatus.text = "Finishing…"
            }
        }
        btnTry.isEnabled = state != DictationEngine.State.WORKING
        setMicPulse(state == DictationEngine.State.RECORDING)
        if (state == DictationEngine.State.IDLE && draftStart >= 0) rewriteInAppDraft()
    }

    private fun setMicPulse(on: Boolean) {
        if (on && micPulse == null) {
            micPulse = ObjectAnimator.ofPropertyValuesHolder(
                btnTry,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f),
            ).apply {
                duration = 650
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        } else if (!on) {
            micPulse?.cancel()
            micPulse = null
            btnTry.scaleX = 1f
            btnTry.scaleY = 1f
        }
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

    // In-app AI rewrite test: the words collect in the box (editable) and, when you tap stop, the
    // text dictated this session is sent to the AI in one call and replaced with its version.
    private var draftStart = -1 // where this session's draft begins in the box; -1 = not in AI mode

    override fun onDraft(phrase: String, isFinal: Boolean) {
        val shown = resultBase + phrase
        txtResult.setText(shown)
        txtResult.setSelection(shown.length)
        if (isFinal) resultBase = "$shown "
    }

    private fun rewriteInAppDraft() {
        val all = txtResult.text.toString()
        val start = draftStart.coerceIn(0, all.length)
        draftStart = -1
        val draft = all.substring(start).trim()
        if (draft.isEmpty()) return
        ui("In-app AI rewrite: sending ${draft.length} chars")
        txtTryStatus.text = "✨ Processing with AI…"
        btnTry.isEnabled = false
        engine.rewrite(draft) { r ->
            btnTry.isEnabled = true
            txtTryStatus.text = "Tap to dictate"
            if (r.ok) {
                RewriteHistory.add(this, draft, r.text, null)
                val now = txtResult.text.toString()
                val before = now.take(start)
                // Replace the draft only if the box still starts the same way (the user may have edited).
                if (now.length >= start) {
                    val shown = before + r.text
                    txtResult.setText(shown)
                    txtResult.setSelection(shown.length)
                }
            } else {
                RewriteHistory.add(this, draft, null, r.why)
                toast("AI rewrite failed: ${r.why}. Your words are left as they were")
            }
        }
    }

    override fun onError(message: String) {
        android.util.Log.w("Speaker.ui", "Error shown in app: $message")
        toast(message)
    }

    // ---------------- helpers ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun copy(label: String, text: CharSequence) =
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))

    private fun toast(msg: String) {
        ui("Toast: $msg")
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
