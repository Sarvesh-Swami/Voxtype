package app.speaker.dictate

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/** Application entry point: starts remote logging before anything else runs. */
class SpeakerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RemoteLog.start(this)
        DictationEngine.restoreLeftoverMute(this)
    }
}

/**
 * Streams this app's own logcat (everything logged in our process, including system warnings
 * about our audio/services) to the "Debug log URL" set in settings, e.g. the ngrok tunnel
 * started by ngrok-logs.ps1 on the PC. Crashes are saved to a file and sent on the next start.
 *
 * Never log API keys. Http.kt logs URLs, status codes and error bodies only.
 */
object RemoteLog {
    const val TAG = "Speaker"

    private val queue = LinkedBlockingDeque<String>()
    private const val MAX_QUEUE = 3000
    @Volatile private var url = ""
    private var lastUploadResult = ""
    private lateinit var appCtx: Context
    private var started = false
    private var source = ""

    fun start(ctx: Context) {
        if (started) return
        started = true
        appCtx = ctx.applicationContext
        url = Prefs(appCtx).logUrl
        val version = runCatching {
            appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName
        }.getOrDefault("?")
        source = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})|$version"
        installCrashHandler()
        Thread(::pumpLogcat, "log-pump").apply { isDaemon = true; start() }
        Thread(::sendLoop, "log-sender").apply { isDaemon = true; start() }
        File(appCtx.filesDir, "last-crash.txt").takeIf { it.exists() }?.let { f ->
            runCatching { f.readLines().forEach { enqueue("PREVIOUS CRASH | $it") } }
            f.delete()
        }
        Log.i(TAG, "App process started: $source")
    }

    fun setUrl(newUrl: String) {
        if (newUrl != url) Log.i("Speaker.logs", "Log URL set to ${newUrl.ifEmpty { "(off)" }}")
        url = newUrl
    }

    /** Sends one line directly and reports the HTTP result (for the "Test" button). */
    fun test(target: String, done: (String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            val result = runCatching {
                val code = post(target, listOf("TEST from phone: ${Build.MODEL} says hello"))
                when (code) {
                    in 200..299 -> "Log URL reached (HTTP $code)"
                    502 -> "Reached ngrok (HTTP 502 = no local server, that's fine: see http://127.0.0.1:4040 on the PC)"
                    404 -> "HTTP 404: ngrok tunnel is not running for this URL"
                    else -> "Log URL answered HTTP $code"
                }
            }.getOrElse { "Could not reach log URL: ${it.message}" }
            main.post { done(result) }
        }.start()
    }

    /** The most recent log lines (kept even when no log URL is set), for Copy/Share logs. */
    fun recentText(): String {
        val lines = synchronized(recent) { recent.toList() }
        return "Speaker logs: ${source.replace('|', ' ')}\n" + lines.joinToString("\n")
    }

    private val recent = ArrayDeque<String>()
    private const val MAX_RECENT = 1500

    private fun enqueue(line: String) {
        synchronized(recent) {
            recent.addLast(line)
            while (recent.size > MAX_RECENT) recent.removeFirst()
        }
        while (queue.size >= MAX_QUEUE) queue.pollFirst()
        queue.offerLast(line)
    }

    /** Reads logcat for our own PID only (no special permission needed). */
    private fun pumpLogcat() {
        runCatching {
            val p = Runtime.getRuntime().exec(
                arrayOf("logcat", "-v", "time", "--pid=${Process.myPid()}", "-T", "1")
            )
            p.inputStream.bufferedReader().forEachLine { if (keep(it)) enqueue(it) }
        }.onFailure { enqueue("logcat pump failed: $it") }
    }

    /**
     * Keeps everything Speaker logs (any level), plus Info/Warning/Error/Fatal from Android itself
     * (audio, permissions, crashes). Drops Android's Verbose/Debug chatter (autofill, layout, touch).
     * Line format (-v time): "MM-DD HH:MM:SS.mmm L/Tag(pid): message".
     */
    private fun keep(line: String): Boolean {
        val level = line.getOrNull(19) ?: return true
        if (line.startsWith("Speaker", startIndex = 21)) return true
        return level != 'V' && level != 'D'
    }

    private fun sendLoop() {
        while (true) {
            val target = url
            if (target.isEmpty()) {
                Thread.sleep(1500)
                continue
            }
            val first = queue.pollFirst(2, TimeUnit.SECONDS) ?: continue
            val batch = mutableListOf(first)
            while (batch.size < 300) batch += queue.pollFirst() ?: break
            // Any HTTP answer counts as delivered: with plain `ngrok http` and no local server, ngrok
            // replies 502 but still records the request body in its inspector (127.0.0.1:4040).
            // Only network failures (tunnel down, no internet) are retried.
            val result = runCatching { "HTTP ${post(target, batch)}" }.getOrElse { "network error: ${it.message}" }
            val sent = result.startsWith("HTTP")
            if (result != lastUploadResult) {
                // Logged once per change (not per batch) so it doesn't feed itself.
                Log.i("Speaker.logs", "Log upload to ${URL(target).host}${URL(target).path}: $result" +
                    if (result == "HTTP 404") " (PC log receiver is old: restart `node tools\\log-server.mjs`)" else "")
                lastUploadResult = result
            }
            if (!sent) {
                batch.asReversed().forEach { queue.offerFirst(it) }
                while (queue.size > MAX_QUEUE) queue.pollLast()
                Thread.sleep(15_000)
            }
            Thread.sleep(2500) // batch lines so the ngrok inspector isn't flooded with tiny requests
        }
    }

    // Deliberately does not use Http.kt (which logs), to avoid a log feedback loop.
    // Plain text so it reads cleanly in the ngrok inspector.
    private fun post(target: String, lines: List<String>): Int {
        val body = ("# ${source.replace("|", " | app ")}\n" + lines.joinToString("\n") + "\n").toByteArray()
        val conn = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            setRequestProperty("ngrok-skip-browser-warning", "1")
            setFixedLengthStreamingMode(body.size)
        }
        try {
            conn.outputStream.use { it.write(body) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val text = "FATAL CRASH in thread '${thread.name}':\n" + Log.getStackTraceString(error)
            runCatching { File(appCtx.filesDir, "last-crash.txt").writeText(text) }
            val target = url
            if (target.isNotEmpty()) {
                // Network isn't allowed on the main thread; try quickly from a helper thread.
                val t = Thread {
                    runCatching {
                        post(target, text.lines())
                        File(appCtx.filesDir, "last-crash.txt").delete()
                    }
                }
                t.start()
                t.join(3000)
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
