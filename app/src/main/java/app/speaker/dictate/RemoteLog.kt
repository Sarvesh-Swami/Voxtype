package app.speaker.dictate

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File

/** Application entry point: starts logging before anything else runs. */
class SpeakerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RemoteLog.start(this)
        DictationEngine.restoreLeftoverMute(this)
    }
}

/** Dictated words never go into the log, only their length. */
fun spoken(text: CharSequence): String = "<${text.length} chars>"

/**
 * Keeps a local, in-memory ring of this app's own logcat for the user-initiated "Copy / Share logs"
 * buttons. Nothing is ever sent anywhere automatically. A crash is saved to a file and added to the
 * log on the next start.
 *
 * Never log API keys. Http.kt logs URLs, status codes and error bodies only.
 */
@android.annotation.SuppressLint("StaticFieldLeak") // holds the Application context only
object RemoteLog {
    const val TAG = "Speaker"

    private lateinit var appCtx: Context
    private var started = false
    private var source = ""

    fun start(ctx: Context) {
        if (started) return
        started = true
        appCtx = ctx.applicationContext
        val version = runCatching {
            appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName
        }.getOrDefault("?")
        source = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) app $version"
        installCrashHandler()
        Thread(::pumpLogcat, "log-pump").apply { isDaemon = true; start() }
        File(appCtx.filesDir, "last-crash.txt").takeIf { it.exists() }?.let { f ->
            runCatching { f.readLines().forEach { remember("PREVIOUS CRASH | $it") } }
            f.delete()
        }
        Log.i(TAG, "App process started: $source")
    }

    /** The most recent log lines, for Copy/Share logs. */
    fun recentText(): String {
        val lines = synchronized(recent) { recent.toList() }
        return "Speaker logs: $source\n" + lines.joinToString("\n")
    }

    private val recent = ArrayDeque<String>()
    private const val MAX_RECENT = 1500

    private fun remember(line: String) {
        synchronized(recent) {
            recent.addLast(line)
            while (recent.size > MAX_RECENT) recent.removeFirst()
        }
    }

    /** Reads logcat for our own PID only (no special permission needed). */
    private fun pumpLogcat() {
        runCatching {
            val p = Runtime.getRuntime().exec(
                arrayOf("logcat", "-v", "time", "--pid=${Process.myPid()}", "-T", "1")
            )
            p.inputStream.bufferedReader().forEachLine { if (keep(it)) remember(it) }
        }.onFailure { remember("logcat pump failed: $it") }
    }

    /**
     * Keeps everything Speaker logs (any level), plus Info/Warning/Error/Fatal from Android itself.
     * Line format (-v time): "MM-DD HH:MM:SS.mmm L/Tag(pid): message".
     */
    private fun keep(line: String): Boolean {
        val level = line.getOrNull(19) ?: return true
        if (line.startsWith("Speaker", startIndex = 21)) return true
        return level != 'V' && level != 'D'
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val text = "FATAL CRASH in thread '${thread.name}':\n" + Log.getStackTraceString(error)
            runCatching { File(appCtx.filesDir, "last-crash.txt").writeText(text) }
            previous?.uncaughtException(thread, error)
        }
    }
}
