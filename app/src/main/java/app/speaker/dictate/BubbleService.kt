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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.hypot

/**
 * Foreground service that owns the floating mic bubble (Android's closest thing to a
 * "Dynamic Island"), the persistent notification with a Dictate action, and the dictation engine.
 *
 * Bubble: tap = start/stop dictation, drag = move, long-press = open settings.
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
            } else if (prefs.stopOnFocusLoss && engine.state == DictationEngine.State.RECORDING && sawFieldThisSession) {
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
        if (state != DictationEngine.State.RECORDING) mainHandler.removeCallbacks(stopOnBlur)
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
        val subtitle = when (currentState) {
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
            log("Bubble tap (state=${engine.state})")
            engine.toggle()
        }
        wm.addView(view, lp)
        log("Bubble overlay added at x=${lp.x} y=${lp.y} size=${size}px screen=${sw}x$sh")
        bubble = view
        params = lp
        renderBubble()
    }

    private fun renderBubble() {
        val b = bubble ?: return
        val (icon, bg) = when (currentState) {
            DictationEngine.State.IDLE -> R.drawable.ic_mic to R.drawable.bg_mic_idle
            DictationEngine.State.RECORDING -> R.drawable.ic_stop to R.drawable.bg_mic_recording
            DictationEngine.State.WORKING -> R.drawable.ic_dots to R.drawable.bg_mic_working
        }
        b.setImageResource(icon)
        b.setBackgroundResource(bg)
        b.contentDescription = when (currentState) {
            DictationEngine.State.IDLE -> "Start dictation"
            DictationEngine.State.RECORDING -> "Stop dictation"
            DictationEngine.State.WORKING -> "Finishing dictation"
        }
        // Pulse for the whole time we're listening, so the bubble (not sounds) shows it's still on.
        // Scales inward so it never gets clipped by the overlay window's edges.
        if (currentState == DictationEngine.State.RECORDING) {
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
                openApp()
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
        log("Bubble long-press: opening settings")
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
            .setContentText(text)
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
