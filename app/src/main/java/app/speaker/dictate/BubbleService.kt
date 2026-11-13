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
import android.widget.TextView
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
        private const val CHANNEL_ID = "dictation"
        private const val NOTIF_ID = 1

        @Volatile var isRunning = false
            private set
        @Volatile var currentState = DictationEngine.State.IDLE
            private set
    }

    private lateinit var engine: DictationEngine
    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        android.util.Log.i("Speaker.bubble", "Bubble service started")
        prefs = Prefs(this)
        engine = DictationEngine(this, this, "bubble")
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            log("startForeground OK (microphone type)")
        } catch (e: Exception) {
            android.util.Log.e("Speaker.bubble", "startForeground FAILED; mic may not work in other apps", e)
        }
        if (Settings.canDrawOverlays(this)) addBubble() else log("No overlay permission: bubble not shown")
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
        log("onStartCommand action=${intent?.action} flags=$flags startId=$startId state=${engine.state}")
        when (intent?.action) {
            ACTION_TOGGLE -> { log("Dictate/Stop via notification or tile (state=${engine.state})"); engine.toggle() }
            ACTION_QUIT -> { log("Quit via notification"); stopSelf() }
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
        bubble?.let { runCatching { wm.removeView(it) }.onFailure { e -> log("removeView failed: $e") } }
        bubble = null
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
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        MicTileService.requestUpdate(this)
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
        val size = dp(56)
        val view = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 22f
            elevation = dp(6).toFloat()
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
        view.setOnTouchListener(BubbleTouch(lp))
        wm.addView(view, lp)
        log("Bubble overlay added at x=${lp.x} y=${lp.y} size=${size}px")
        bubble = view
        params = lp
        renderBubble()
    }

    private fun renderBubble() {
        val b = bubble ?: return
        val (label, color) = when (currentState) {
            DictationEngine.State.IDLE -> "🎤" to 0xEE3355FF.toInt()
            DictationEngine.State.RECORDING -> "⏹" to 0xEEE53935.toInt()
            DictationEngine.State.WORKING -> "…" to 0xEEFFA000.toInt()
        }
        b.text = label
        b.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(2), 0x55FFFFFF)
        }
        // Pulse for the whole time we're listening, so the bubble (not sounds) shows it's still on.
        if (currentState == DictationEngine.State.RECORDING) {
            if (pulse == null) {
                pulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(
                    b,
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.15f),
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.15f),
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

    private inner class BubbleTouch(private val lp: WindowManager.LayoutParams) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop
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
                    when {
                        dragging -> {
                            prefs.bubbleX = lp.x; prefs.bubbleY = lp.y
                            log("Bubble dragged to x=${lp.x} y=${lp.y}")
                        }
                        !longPressed -> {
                            log("Bubble tap (state=${engine.state})")
                            engine.toggle()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> v.removeCallbacks(longPress)
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
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Speaker")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, action, toggle).build())
            .addAction(Notification.Action.Builder(null, "Quit", quit).build())
            .build()
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
