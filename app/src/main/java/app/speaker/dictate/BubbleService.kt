package app.speaker.dictate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot

/**
 * Foreground service that owns the floating mic bubble (Android's closest thing to a
 * "Dynamic Island"), the persistent notification with a Dictate action, and the dictation engine.
 *
 * Bubble: tap = start/stop dictation (AI rewrite on: stop and send the draft), drag = move,
 * long-press = quick menu (AI rewrite on/off, open Speaker).
 */
class BubbleService : Service(), DictationEngine.Listener {

    companion object {
        const val ACTION_TOGGLE = "app.speaker.dictate.TOGGLE"
        const val ACTION_QUIT = "app.speaker.dictate.QUIT"
        const val ACTION_REFRESH = "app.speaker.dictate.REFRESH"
        private const val CHANNEL_ID = "dictation"
        private const val NOTIF_ID = 1
        private const val CUSTOM_QUIT = "quit"

        /** Re-applies bubble/island settings if the service is running. */
        fun refresh(ctx: Context) {
            if (isRunning) ctx.startService(Intent(ctx, BubbleService::class.java).setAction(ACTION_REFRESH))
        }

        @Volatile var isRunning = false
            private set
        @Volatile var currentState = DictationEngine.State.IDLE
            private set
    }

    private lateinit var engine: DictationEngine
    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private var bubble: ImageView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private var failedToStart = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        android.util.Log.i("Speaker.bubble", "Bubble service started")
        prefs = Prefs(this)
        engine = DictationEngine(this, this, "bubble")
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
        if (prefs.islandMedia) startMediaSession()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            log("startForeground OK (microphone type)")
        } catch (e: Exception) {
            // Without foreground status Android kills the service (and the app) a few seconds later,
            // and the mic would be blocked anyway. Stop cleanly and tell the user instead.
            android.util.Log.e("Speaker.bubble", "startForeground FAILED; stopping the bubble", e)
            toast("Speaker couldn't start the bubble (${e.javaClass.simpleName}). Open Speaker and try again.")
            failedToStart = true
            stopSelf()
            return
        }
        when {
            !prefs.showBubble -> log("Floating bubble turned off in settings")
            Settings.canDrawOverlays(this) -> addBubble()
            else -> log("No overlay permission: bubble not shown")
        }
        TypeAccessibilityService.editorFocusListener = focusListener
        MicTileService.requestUpdate(this)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        log("onTaskRemoved: app swiped away from Recents (service keeps running, state=${engine.state})")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        log("onTrimMemory level=$level (state=${engine.state})")
        super.onTrimMemory(level)
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        log("onLowMemory: system is low on memory and may kill this service")
        @Suppress("DEPRECATION")
        super.onLowMemory()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        log("onTimeout: Android ended the foreground service (type=$fgsType)")
        super.onTimeout(startId, fgsType)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (failedToStart) return START_NOT_STICKY
        log("onStartCommand action=${intent?.action} flags=$flags startId=$startId state=${engine.state}")
        when (intent?.action) {
            ACTION_TOGGLE -> { log("Dictate/Stop via notification or tile (state=${engine.state})"); engine.toggle() }
            ACTION_QUIT -> { log("Quit via notification"); stopSelf() }
            ACTION_REFRESH -> applySettings()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        log("Bubble service stopped")
        if (TypeAccessibilityService.editorFocusListener === focusListener) TypeAccessibilityService.editorFocusListener = null
        mainHandler.removeCallbacks(stopOnBlur)
        isRunning = false
        currentState = DictationEngine.State.IDLE
        engine.release()
        stopMediaSession()
        removeBubble()
        MicTileService.requestUpdate(this)
        super.onDestroy()
    }

    // ---- DictationEngine.Listener ----

    private var copiedThisSession = false
    private var sessionChars = 0

    // ---- Auto-stop when the text field loses focus ----

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var sawFieldThisSession = false
    private val stopOnBlur = Runnable {
        if (engine.state == DictationEngine.State.RECORDING) {
            log("Text field stayed unfocused for 1.5s: stopping dictation")
            engine.stopIfActive("text field lost focus")
            toast("Dictation stopped (left the text field)")
        }
    }

    private val focusListener = TypeAccessibilityService.EditorFocusListener { focused, pkg ->
        mainHandler.post {
            log("Field focus changed: focused=$focused pkg=$pkg (dictation=${engine.state}, stopOnBlur=${prefs.stopOnFocusLoss})")
            if (focused) {
                sawFieldThisSession = true
                mainHandler.removeCallbacks(stopOnBlur) // came back (or moved to another field): keep going
            } else if (!draftSession && prefs.stopOnFocusLoss && engine.state == DictationEngine.State.RECORDING && sawFieldThisSession) {
                // (AI drafts aren't typed live, so leaving the field, or editing the card, doesn't matter.)
                // Debounced: apps briefly drop and re-take focus, e.g. right after sending a message.
                mainHandler.postDelayed(stopOnBlur, 1500)
            }
        }
    }

    override fun onStateChanged(state: DictationEngine.State) {
        val previous = currentState
        currentState = state
        log("State: $previous -> $state")
        if (state == DictationEngine.State.RECORDING && previous == DictationEngine.State.IDLE) {
            copiedThisSession = false
            sessionChars = 0
            LiveTyper.reset()
            sawFieldThisSession = TypeAccessibilityService.instance?.editorActive() == true
            log("Session started; a text field is ${if (sawFieldThisSession) "focused" else "NOT focused yet"}")
        }
        if (state == DictationEngine.State.RECORDING && previous == DictationEngine.State.IDLE) {
            draftSession = engine.draftMode
            sendWhenIdle = false; discardWhenIdle = false; typeRawWhenIdle = false
            cardError = null
            if (draftSession) showDraftCard()
        }
        if (state != DictationEngine.State.RECORDING) mainHandler.removeCallbacks(stopOnBlur)
        if (state == DictationEngine.State.IDLE && draftSession) {
            cardLive?.visibility = View.GONE
            when {
                discardWhenIdle -> { discardWhenIdle = false; clearDraft(); hideCard() }
                typeRawWhenIdle -> {
                    typeRawWhenIdle = false
                    val text = draftText().trim()
                    clearDraft(); hideCard()
                    if (text.isNotEmpty()) deliver(text, ai = false)
                }
                // No card on screen (bubble hidden) means nowhere to press Send: send right away.
                sendWhenIdle || card == null -> {
                    sendWhenIdle = false
                    if (hasDraft) sendDraft("Session ended") else hideCard()
                }
                !hasDraft -> hideCard()
            }
        }
        updateCard()
        if (state == DictationEngine.State.IDLE && copiedThisSession) {
            toast("Dictation ($sessionChars chars) is on the clipboard. Long-press, then Paste")
        }
        renderBubble()
        updateMediaState()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        MicTileService.requestUpdate(this)
    }

    private fun applySettings() {
        log("Applying settings: showBubble=${prefs.showBubble} islandMedia=${prefs.islandMedia}")
        if (prefs.showBubble && bubble == null && Settings.canDrawOverlays(this)) addBubble()
        if (!prefs.showBubble) removeBubble()
        if (prefs.islandMedia && session == null) startMediaSession()
        if (!prefs.islandMedia) stopMediaSession()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun removeBubble() {
        hideCard()
        hideMenu()
        pulse?.cancel(); pulse = null
        bubble?.let { runCatching { wm.removeView(it) }.onFailure { e -> log("removeView failed: $e") } }
        bubble = null
    }

    // ---- Island / media controls ----
    // realme (ColorOS) shows its island ("Fluid Cloud" / Live Alerts) for apps with an active media
    // session, which is what YouTube and music players use. Dictation maps onto it as
    // play = start dictating, pause = stop, so it can be started from the island.

    private var session: android.media.session.MediaSession? = null

    private fun startMediaSession() {
        val s = android.media.session.MediaSession(this, "SpeakerDictation")
        s.setCallback(object : android.media.session.MediaSession.Callback() {
            override fun onPlay() {
                log("Island/media: PLAY pressed (state=${engine.state})")
                if (engine.state == DictationEngine.State.IDLE) engine.toggle()
            }
            override fun onPause() {
                log("Island/media: PAUSE pressed (state=${engine.state})")
                engine.stopIfActive("island pause")
            }
            override fun onStop() {
                log("Island/media: STOP pressed (state=${engine.state})")
                engine.stopIfActive("island stop")
            }
            // Headphone / Bluetooth play-pause buttons go to the most recent media session. Never let
            // one of those turn the microphone on: only the on-screen island/media controls may.
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                log("Island/media: ignored a hardware media button (it must not start the microphone)")
                return true
            }

            override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                log("Island/media: custom action $action")
                if (action == CUSTOM_QUIT) stopSelf()
            }
        })
        s.setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        session = s
        updateMediaState()
        s.isActive = true
        log("Island/media session started")
    }

    private fun stopMediaSession() {
        session?.let {
            runCatching { it.isActive = false; it.release() }
            log("Island/media session stopped")
        }
        session = null
    }

    private var artwork: android.graphics.Bitmap? = null

    private fun artwork(): android.graphics.Bitmap = artwork ?: run {
        val size = dp(96)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        getDrawable(R.drawable.ic_launcher_bg)?.apply { setBounds(0, 0, size, size); draw(c) }
        // The adaptive-icon foreground is drawn on a 108dp canvas with the art in the middle 72dp.
        val pad = -size / 6
        getDrawable(R.drawable.ic_launcher_fg)?.apply { setBounds(pad, pad, size - pad, size - pad); draw(c) }
        bmp.also { artwork = it }
    }

    private fun updateMediaState() {
        val s = session ?: return
        val recording = currentState != DictationEngine.State.IDLE
        val subtitle = if (processing) "✨ Processing with AI…" else when (currentState) {
            DictationEngine.State.IDLE -> "Tap ▶ to dictate"
            DictationEngine.State.RECORDING -> "Listening… tap ⏸ to stop"
            DictationEngine.State.WORKING -> "Finishing…"
        }
        s.setMetadata(
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "Speaker")
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .putBitmap(android.media.MediaMetadata.METADATA_KEY_ART, artwork())
                .build()
        )
        val ps = android.media.session.PlaybackState.Builder()
            .setActions(
                android.media.session.PlaybackState.ACTION_PLAY or
                    android.media.session.PlaybackState.ACTION_PAUSE or
                    android.media.session.PlaybackState.ACTION_PLAY_PAUSE or
                    android.media.session.PlaybackState.ACTION_STOP
            )
            .setState(
                if (recording) android.media.session.PlaybackState.STATE_PLAYING
                else android.media.session.PlaybackState.STATE_PAUSED,
                android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (recording) 1f else 0f
            )
            .addCustomAction(
                android.media.session.PlaybackState.CustomAction.Builder(CUSTOM_QUIT, "Quit", R.drawable.ic_clear).build()
            )
            .build()
        s.setPlaybackState(ps)
        log("Island/media state -> ${if (recording) "PLAYING" else "PAUSED"} (${currentState})")
    }

    override fun onText(text: String, isFinal: Boolean, sessionText: String) {
        sessionChars = sessionText.length
        val result = LiveTyper.update(this, text, isFinal, sessionText)
        if (isFinal) log("Phrase settled: ${text.length} chars -> $result")
        if (result == LiveTyper.Result.COPIED && !copiedThisSession) {
            copiedThisSession = true
            toast("Couldn't type into this app. Keep talking; everything goes to the clipboard")
        }
    }

    override fun onError(message: String) = toast(message)

    // ---- AI rewrite: draft card next to the bubble ----
    // With AI rewrite on, nothing is typed while you speak. Your words collect in this card, where
    // you can scroll and edit them. ✨ Send (or a tap on the bubble) sends the whole text to the AI in
    // one call; the result is typed into the focused field, or copied to the clipboard if none is.
    // The card exists only in AI rewrite mode, and never shows the AI's text.

    /** The current (or last) session collects an AI draft instead of typing. */
    private var draftSession = false
    private var processing = false
    private var sendWhenIdle = false
    private var discardWhenIdle = false
    private var typeRawWhenIdle = false
    private var cardError: String? = null
    /** Holds the draft while no card is on screen (bubble hidden), so nothing is lost. */
    private val offscreenDraft = StringBuilder()

    private var card: LinearLayout? = null
    private var cardEdit: EditText? = null
    private var cardLive: TextView? = null
    private var cardStatus: TextView? = null
    private var btnClear: TextView? = null
    private var btnMore: TextView? = null
    private var btnSend: TextView? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private var cardAnim: android.animation.ObjectAnimator? = null
    private var cardEditing = false

    private fun draftText(): String = cardEdit?.text?.toString() ?: offscreenDraft.toString()
    private val hasDraft get() = draftText().isNotBlank()

    override fun onDraft(phrase: String, isFinal: Boolean) {
        if (!draftSession || discardWhenIdle) return
        if (card == null) showDraftCard()
        if (!isFinal) {
            cardLive?.apply { text = "🎤 $phrase"; visibility = View.VISIBLE }
            moveCard()
            return
        }
        cardLive?.visibility = View.GONE
        val current: CharSequence = cardEdit?.text ?: offscreenDraft
        val sep = if (current.isNotEmpty() && !current.last().isWhitespace() && phrase.first() !in ".,!?;:)") " " else ""
        val ed = cardEdit
        if (ed != null) {
            ed.text.append(sep + phrase)
            if (!cardEditing) scrollDraftToEnd()
        } else {
            offscreenDraft.append(sep).append(phrase)
        }
        updateCard()
    }

    /** Main action in AI mode (bubble tap, ✨ Send): stop listening if needed, then send the draft. */
    private fun sendOrStop(why: String) {
        if (processing) { toast("Still processing with AI…"); return }
        when (engine.state) {
            DictationEngine.State.RECORDING -> {
                log("$why: stop listening, then send the draft to AI")
                sendWhenIdle = true
                engine.stopIfActive("$why: send to AI")
            }
            DictationEngine.State.WORKING -> { log("$why: will send when the last phrase is in"); sendWhenIdle = true }
            DictationEngine.State.IDLE -> sendDraft(why)
        }
    }

    private fun sendDraft(why: String) {
        finishEditing()
        val text = draftText().trim()
        if (text.isEmpty()) {
            toast("Nothing to send yet. Speak first")
            hideCard()
            return
        }
        cardError = null
        if (!prefs.polishEnabled) {
            log("$why: AI rewrite is off; typing the draft as it is (${text.length} chars)")
            clearDraft(); hideCard()
            deliver(text, ai = false)
            return
        }
        processing = true
        log("$why: sending the draft to AI (${text.length} chars)")
        statusChanged()
        engine.rewrite(text) { r ->
            processing = false
            if (r.ok) {
                RewriteHistory.add(this, text, r.text, null)
                clearDraft(); hideCard()
                deliver(r.text, ai = true)
            } else {
                RewriteHistory.add(this, text, null, r.why)
                if (card == null) {
                    // No card to retry from (bubble hidden): don't lose the words.
                    toast("AI rewrite failed: ${r.why}. Your own words are used instead")
                    clearDraft()
                    deliver(text, ai = false)
                } else {
                    cardError = "⚠ ${r.why ?: "AI rewrite failed"}. Edit or tap ✨ Send to retry"
                    toast("AI rewrite failed: ${r.why}")
                }
            }
            statusChanged()
        }
    }

    /** Types [text] into the focused field, or puts it on the clipboard when no field is focused. */
    private fun deliver(text: String, ai: Boolean) {
        // Let the app underneath get its text field back first (the card may just have had focus).
        mainHandler.postDelayed({
            val result = LiveTyper.insert(this, text)
            log("Delivered ${if (ai) "AI" else "original"} text (${text.length} chars): $result")
            if (result == LiveTyper.Result.COPIED) {
                toast(if (ai) "No text box selected: the AI text is on the clipboard. Long-press a text box, then Paste"
                else "No text box selected: your words are on the clipboard")
            }
        }, 350)
    }

    private fun clearDraft() {
        cardEdit?.setText("")
        offscreenDraft.setLength(0)
        cardLive?.visibility = View.GONE
        cardError = null
    }

    private fun discardDraft() {
        log("Draft discarded by the user (${draftText().length} chars)")
        finishEditing()
        sendWhenIdle = false
        clearDraft()
        if (engine.state != DictationEngine.State.IDLE) {
            discardWhenIdle = true
            engine.stopIfActive("draft discarded")
        }
        hideCard()
    }

    /** Bubble look, card, notification and island after processing/draft changes. */
    private fun statusChanged() {
        renderBubble()
        updateCard()
        updateMediaState()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun showDraftCard() {
        if (bubble == null) return // the card belongs to the bubble; the draft is kept off screen meanwhile
        if (card == null && !createCard()) return
        updateCard()
    }

    /** Status line, buttons and position for the current state. */
    private fun updateCard() {
        val c = card ?: return
        val state = engine.state
        val status = when {
            processing -> "✨ Processing with AI…"
            cardError != null -> cardError
            cardEditing -> "Editing. Tap ✨ Send when ready"
            state == DictationEngine.State.RECORDING -> "Listening… tap ✨ Send or the bubble when done"
            state == DictationEngine.State.WORKING -> "Finishing…"
            hasDraft -> "Tap the text to edit it, then ✨ Send"
            else -> "Speak, then tap ✨ Send"
        }
        cardStatus?.text = status
        cardStatus?.setTextColor(if (cardError != null && !processing) 0xFFF6AE2D.toInt() else 0xFF8AB4F8.toInt())
        cardEdit?.isEnabled = !processing
        cardEdit?.alpha = if (processing) 0.6f else 1f
        val idle = state == DictationEngine.State.IDLE
        btnClear?.visibility = if (processing) View.GONE else View.VISIBLE
        btnMore?.visibility = if (!processing && idle) View.VISIBLE else View.GONE
        btnSend?.visibility = if (processing) View.GONE else View.VISIBLE
        btnSend?.text = if (cardError != null) "✨ Retry" else "✨ Send"
        cardAnim?.cancel(); cardAnim = null
        cardStatus?.alpha = 1f
        if (processing) cardStatus?.let { s ->
            cardAnim = android.animation.ObjectAnimator.ofFloat(s, View.ALPHA, 1f, 0.4f).apply {
                duration = 600
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
                start()
            }
        }
        c.alpha = 1f
        moveCard()
    }

    private fun cardWidth() = minOf(dp(290), screenSize().first - dp(58) - dp(44))

    private fun createCard(): Boolean {
        val w = cardWidth()
        val edit = EditText(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x80FFFFFF.toInt())
            textSize = 15f
            hint = "Speak… your words appear here"
            background = null
            setPadding(0, 0, 0, 0)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 6 // set after inputType (which resets it); longer drafts scroll inside the card
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT)
            setText(offscreenDraft)
        }
        offscreenDraft.setLength(0)
        // A tap (not a scroll) on the text starts editing: the card takes focus so the keyboard opens.
        val taps = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean { startEditing(); return false }
        })
        edit.setOnTouchListener { _, e -> taps.onTouchEvent(e); false }
        edit.setOnClickListener { startEditing() } // TalkBack double-taps arrive here, not as touches
        edit.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP && cardEditing) { finishEditing(); true } else false
        }
        val live = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.START // newest words stay visible
            maxWidth = w
            setPadding(0, dp(4), 0, 0)
            visibility = View.GONE
        }
        val status = TextView(this).apply {
            textSize = 12f
            maxWidth = w
            setPadding(0, dp(6), 0, 0)
        }
        fun chip(label: String, primary: Boolean, onTap: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(if (primary) 0xFF202124.toInt() else 0xFFFFFFFF.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (primary) 0xFF8AB4F8.toInt() else 0x33FFFFFF)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
            setOnClickListener { onTap() }
        }
        val clear = chip("✕", false) { discardDraft() }.apply { contentDescription = "Discard this text" }
        val more = chip("🎤 More", false) {
            log("Card: dictate more into the draft")
            finishEditing()
            if (engine.state == DictationEngine.State.IDLE) engine.toggle()
        }
        val send = chip("✨ Send", true) { sendOrStop("Send button") }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
            addView(clear); addView(more); addView(send)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xF0202124.toInt())
            }
            elevation = dp(4).toFloat()
            addView(edit); addView(live); addView(status); addView(row)
            // A touch anywhere else ends editing and hands the keyboard back to the app underneath.
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) finishEditing(); false }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable until the user taps the text, so the app underneath keeps its field and keyboard.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START; x = 0; y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        return runCatching { wm.addView(layout, lp) }.fold(
            onSuccess = {
                card = layout; cardEdit = edit; cardLive = live; cardStatus = status
                btnClear = clear; btnMore = more; btnSend = send; cardParams = lp
                if (edit.length() > 0) scrollDraftToEnd()
                true
            },
            onFailure = { log("Draft card could not be shown: $it"); edit.text?.let { offscreenDraft.append(it) }; false },
        )
    }

    private fun scrollDraftToEnd() {
        val ed = cardEdit ?: return
        ed.setSelection(ed.length())
        ed.post {
            val l = ed.layout ?: return@post
            ed.scrollTo(0, (l.height - (ed.height - ed.totalPaddingTop - ed.totalPaddingBottom)).coerceAtLeast(0))
        }
    }

    private fun startEditing() {
        if (cardEditing || processing) return
        val c = card ?: return
        val lp = cardParams ?: return
        cardEditing = true
        log("Draft card: editing (card takes the keyboard)")
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        // Keep the card in the top half so the keyboard doesn't cover it.
        lp.y = lp.y.coerceAtMost(screenSize().second * 45 / 100 - c.height).coerceAtLeast(dp(24))
        runCatching { wm.updateViewLayout(c, lp) }
        cardEdit?.let { ed ->
            ed.requestFocus()
            ed.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(ed, 0) }, 150)
        }
        updateCard()
    }

    private fun finishEditing() {
        if (!cardEditing) return
        cardEditing = false
        log("Draft card: editing done (keyboard goes back to the app)")
        cardEdit?.let { ed ->
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(ed.windowToken, 0)
            ed.clearFocus()
        }
        val c = card
        val lp = cardParams
        if (c != null && lp != null) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            runCatching { wm.updateViewLayout(c, lp) }
        }
        updateCard()
    }

    /** Beside the bubble, on the side facing the middle of the screen, vertically centred on it. */
    private fun moveCard() {
        if (cardEditing) return // stays where the keyboard can't cover it
        val c = card ?: return
        val lp = cardParams ?: return
        val bp = params ?: return
        c.measure(
            View.MeasureSpec.makeMeasureSpec(screenSize().first, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.UNSPECIFIED
        )
        val size = dp(58)
        val (sw, sh) = screenSize()
        lp.x = if (bp.x + size / 2 > sw / 2) bp.x - c.measuredWidth - dp(8) else bp.x + size + dp(8)
        lp.y = (bp.y + (size - c.measuredHeight) / 2).coerceIn(dp(24), (sh - c.measuredHeight - dp(24)).coerceAtLeast(dp(24)))
        runCatching { wm.updateViewLayout(c, lp) }
    }

    private fun hideCard() {
        finishEditing()
        cardAnim?.cancel(); cardAnim = null
        // Keep any draft text (e.g. the bubble was turned off mid-draft).
        cardEdit?.text?.let { if (it.isNotBlank()) { offscreenDraft.setLength(0); offscreenDraft.append(it) } }
        card?.let { runCatching { wm.removeView(it) } }
        card = null; cardEdit = null; cardLive = null; cardStatus = null
        btnClear = null; btnMore = null; btnSend = null; cardParams = null
    }

    // ---- Long-press quick menu ----

    private var menu: LinearLayout? = null
    private val hideMenuLater = Runnable { hideMenu() }

    private fun showQuickMenu() {
        hideMenu()
        finishEditing()
        val bp = params ?: return
        log("Bubble long-press: quick menu")
        bubble?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        fun item(label: String, onTap: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(16), dp(12), dp(20), dp(12))
            setOnClickListener { hideMenu(); onTap() }
        }
        val aiOn = prefs.polishEnabled
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xF5202124.toInt())
            }
            elevation = dp(6).toFloat()
            addView(item(if (aiOn) "✨ Turn AI rewrite off" else "✨ Turn AI rewrite on") { setAiRewrite(!aiOn) })
            addView(item("⚙ Open Speaker") { openApp() })
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) hideMenu(); false }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        layout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val size = dp(58)
        val (sw, sh) = screenSize()
        lp.x = if (bp.x + size / 2 > sw / 2) bp.x - layout.measuredWidth - dp(8) else bp.x + size + dp(8)
        lp.y = bp.y.coerceIn(dp(24), (sh - layout.measuredHeight - dp(24)).coerceAtLeast(dp(24)))
        runCatching { wm.addView(layout, lp) }
            .onSuccess { menu = layout; mainHandler.postDelayed(hideMenuLater, 6000) }
            .onFailure { log("Quick menu could not be shown: $it"); openApp() }
    }

    private fun hideMenu() {
        mainHandler.removeCallbacks(hideMenuLater)
        menu?.let { runCatching { wm.removeView(it) } }
        menu = null
    }

    private fun setAiRewrite(on: Boolean) {
        if (on) {
            val llm = LlmProvider.from(prefs.llmProvider)
            if (prefs.apiKey(llm.vendor).isBlank() || prefs.llmModel(llm).isBlank()) {
                log("Quick menu: AI rewrite can't be turned on yet (no key or model for ${llm.id})")
                toast("Set up AI rewrite in Speaker first: provider, API key and model")
                openApp()
                return
            }
            prefs.polishEnabled = true
            log("Quick menu: AI rewrite ON")
            toast(if (engine.state == DictationEngine.State.IDLE) "AI rewrite on. Speak, then tap ✨ Send or the bubble"
            else "AI rewrite on from your next dictation")
        } else {
            prefs.polishEnabled = false
            log("Quick menu: AI rewrite OFF (draft ${draftText().length} chars, state=${engine.state})")
            when {
                draftSession && engine.state != DictationEngine.State.IDLE -> {
                    typeRawWhenIdle = true
                    sendWhenIdle = false
                    engine.stopIfActive("AI rewrite turned off")
                    toast("AI rewrite off. Typing what you said so far")
                }
                hasDraft && !processing -> {
                    val text = draftText().trim()
                    clearDraft(); hideCard()
                    deliver(text, ai = false)
                    toast("AI rewrite off. Typed your words as they were")
                }
                else -> toast("AI rewrite off. Words are typed as you speak")
            }
        }
        statusChanged()
    }

    // ---- Bubble ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun addBubble() {
        val size = dp(58)
        val view = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            elevation = dp(6).toFloat()
            contentDescription = "Speaker dictation"
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable: the app underneath keeps keyboard focus, so text lands in its field.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.bubbleX
            y = prefs.bubbleY
        }
        val (sw, sh) = screenSize()
        val savedX = lp.x; val savedY = lp.y
        clampOnScreen(lp, size, snap = false)
        if (lp.x != savedX || lp.y != savedY) {
            log("Saved bubble position x=$savedX y=$savedY was off-screen (screen ${sw}x$sh); moved to x=${lp.x} y=${lp.y}")
            prefs.bubbleX = lp.x; prefs.bubbleY = lp.y
        }
        view.setOnTouchListener(BubbleTouch(lp))
        // Taps (and TalkBack double-taps, which never reach onTouch) arrive here.
        view.setOnClickListener {
            log("Bubble tap (state=${engine.state} draft=$draftSession hasDraft=$hasDraft processing=$processing)")
            hideMenu()
            when {
                processing -> toast("Still processing with AI…")
                // AI mode: the bubble is the Send button (stops listening first).
                draftSession && engine.state != DictationEngine.State.IDLE -> sendOrStop("Bubble tap")
                engine.state == DictationEngine.State.IDLE && hasDraft -> sendDraft("Bubble tap")
                else -> engine.toggle()
            }
        }
        wm.addView(view, lp)
        log("Bubble overlay added at x=${lp.x} y=${lp.y} size=${size}px screen=${sw}x$sh")
        bubble = view
        params = lp
        renderBubble()
    }

    private fun renderBubble() {
        val b = bubble ?: return
        val (icon, bg) = when {
            processing -> R.drawable.ic_dots to R.drawable.bg_mic_working
            else -> when (currentState) {
                DictationEngine.State.IDLE -> R.drawable.ic_mic to R.drawable.bg_mic_idle
                DictationEngine.State.RECORDING -> R.drawable.ic_stop to R.drawable.bg_mic_recording
                DictationEngine.State.WORKING -> R.drawable.ic_dots to R.drawable.bg_mic_working
            }
        }
        b.setImageResource(icon)
        b.setBackgroundResource(bg)
        b.contentDescription = when {
            processing -> "Processing with AI"
            currentState == DictationEngine.State.IDLE && hasDraft -> "Send to AI"
            currentState == DictationEngine.State.IDLE -> "Start dictation"
            currentState == DictationEngine.State.RECORDING && draftSession -> "Stop and send to AI"
            currentState == DictationEngine.State.RECORDING -> "Stop dictation"
            else -> "Finishing dictation"
        }
        // Pulse for the whole time we're listening (or the AI is working), so the bubble shows it.
        // Scales inward so it never gets clipped by the overlay window's edges.
        if (currentState == DictationEngine.State.RECORDING || processing) {
            if (pulse == null) {
                pulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(
                    b,
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.86f),
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.86f),
                ).apply {
                    duration = 700
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    start()
                }
            }
        } else {
            pulse?.cancel()
            pulse = null
            b.scaleX = 1f
            b.scaleY = 1f
        }
    }

    private var pulse: android.animation.ObjectAnimator? = null

    private fun screenSize(): Pair<Int, Int> =
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        }

    /**
     * Keeps the whole bubble on screen (it used to be draggable past the edge, where it was
     * mostly hidden and the system back-gesture zone swallowed taps). With [snap], it also
     * docks to the nearer side, a little away from the edge so taps aren't read as "back".
     */
    private fun clampOnScreen(lp: WindowManager.LayoutParams, size: Int, snap: Boolean) {
        val (sw, sh) = screenSize()
        val margin = dp(12)
        val minX = margin
        val maxX = (sw - size - margin).coerceAtLeast(minX)
        val minY = dp(48)
        val maxY = (sh - size - dp(72)).coerceAtLeast(minY)
        lp.x = if (snap) { if (lp.x + size / 2 < sw / 2) minX else maxX } else lp.x.coerceIn(minX, maxX)
        lp.y = lp.y.coerceIn(minY, maxY)
    }

    private inner class BubbleTouch(private val lp: WindowManager.LayoutParams) : View.OnTouchListener {
        // Generous threshold: a slightly shaky finger on a small floating button should still
        // count as a tap, not a drag (two taps were lost as tiny "drags" in v0.8.0).
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop * 2
        private var downTime = 0L
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable {
            if (!dragging) {
                longPressed = true
                showQuickMenu()
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    downX = e.rawX; downY = e.rawY
                    dragging = false; longPressed = false
                    downTime = e.eventTime
                    v.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        v.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        wm.updateViewLayout(v, lp)
                        moveCard()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    val moved = hypot(e.rawX - downX, e.rawY - downY)
                    val heldMs = e.eventTime - downTime
                    // A quick touch that barely moved is a tap, even if it crossed the drag threshold.
                    if (dragging && heldMs < 250 && moved < slop * 3) {
                        log("Short touch (${heldMs}ms, moved ${moved.toInt()}px) treated as a tap, not a drag")
                        lp.x = startX; lp.y = startY
                        wm.updateViewLayout(v, lp)
                        dragging = false
                    }
                    when {
                        dragging -> {
                            val rawX = lp.x; val rawY = lp.y
                            clampOnScreen(lp, v.width, snap = true)
                            wm.updateViewLayout(v, lp)
                            moveCard()
                            prefs.bubbleX = lp.x; prefs.bubbleY = lp.y
                            log("Bubble dragged to x=$rawX y=$rawY -> docked at x=${lp.x} y=${lp.y} (${heldMs}ms)")
                        }
                        !longPressed -> {
                            log("Bubble touch: ${heldMs}ms, moved ${moved.toInt()}px -> click")
                            v.performClick()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    log("Bubble touch CANCELLED by the system (edge/back gesture?) at x=${lp.x} y=${lp.y}")
                }
            }
            return true
        }
    }

    private fun log(msg: String) = android.util.Log.i("Speaker.bubble", msg)

    private fun openApp() {
        log("Opening Speaker")
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- Notification ----

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Dictation", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps the dictation bubble available"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_TOGGLE), flags
        )
        val quit = PendingIntent.getService(
            this, 2, Intent(this, BubbleService::class.java).setAction(ACTION_QUIT), flags
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val (text, action) = when (currentState) {
            DictationEngine.State.IDLE -> "Ready. Tap the bubble or Dictate" to "🎤 Dictate"
            DictationEngine.State.RECORDING -> "Listening continuously. Tap Stop when done" to "⏹ Stop"
            DictationEngine.State.WORKING -> "Finishing last phrase…" to "…"
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Speaker")
            .setContentText(when {
                processing -> "✨ Processing with AI… the text is typed when it's ready"
                draftSession && currentState == DictationEngine.State.RECORDING ->
                    "Listening (AI rewrite). Tap the bubble or ✨ Send when done"
                else -> text
            })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        val s = session
        if (s != null) {
            // Media-style notification bound to the session: this is what the island and the
            // media-controls panel pick up (same mechanism as YouTube / music apps).
            val icon = if (currentState == DictationEngine.State.IDLE) R.drawable.ic_mic else R.drawable.ic_stop
            builder
                .setLargeIcon(artwork())
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), action, toggle).build())
                .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_clear), "Quit", quit).build())
                .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0))
        } else {
            builder
                .addAction(Notification.Action.Builder(null, action, toggle).build())
                .addAction(Notification.Action.Builder(null, "Quit", quit).build())
        }
        return builder.build()
    }

    private var lastToast = ""
    private var lastToastAt = 0L

    /** Skips repeats of the same message within 5 s so errors don't stack up for minutes. */
    private fun toast(msg: String) {
        val now = System.currentTimeMillis()
        if (msg == lastToast && now - lastToastAt < 5000) return
        lastToast = msg
        lastToastAt = now
        log("Toast: $msg")
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
