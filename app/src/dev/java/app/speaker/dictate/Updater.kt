package app.speaker.dictate

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from the PC. The log server (tools/log-server.mjs, reached through the same ngrok URL as
 * the logs) serves /update/latest.json and /update/apk from the project's dist/ folder. The APK is
 * streamed into Android's PackageInstaller; Android shows one "Update" confirmation and only accepts
 * builds signed with the same key, so permissions and settings are kept.
 */
object Updater {
    private const val TAG = "Speaker.update"
    private val main = Handler(Looper.getMainLooper())

    data class Info(val versionCode: Int, val versionName: String, val size: Long)

    /** https://host from the log URL (the update server is the same PC). */
    private fun baseUrl(ctx: Context): String? {
        val u = Prefs(ctx).logUrl
        if (u.isEmpty()) return null
        val uri = Uri.parse(u)
        return "${uri.scheme}://${uri.authority}"
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 60_000
            setRequestProperty("ngrok-skip-browser-warning", "1")
        }

    private fun fetchInfo(base: String): Info {
        val conn = open("$base/update/latest.json")
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("update server answered HTTP $code ${body.take(120)}")
            val j = JSONObject(body)
            return Info(j.getInt("versionCode"), j.getString("versionName"), j.optLong("size", -1))
        } finally {
            conn.disconnect()
        }
    }

    /** Background check; [done] gets null if the server can't be reached. */
    fun check(ctx: Context, done: (Info?) -> Unit) {
        val base = baseUrl(ctx) ?: return done(null)
        Thread {
            val info = runCatching { fetchInfo(base) }
                .onFailure { Log.i(TAG, "Update check failed: ${it.message}") }
                .getOrNull()
            if (info != null) Log.i(TAG, "Update check: server has v${info.versionName} (${info.versionCode}), installed ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            main.post { done(info) }
        }.start()
    }

    fun checkAndInstall(activity: Activity, done: (String) -> Unit) {
        val base = baseUrl(activity) ?: return done("Set the Debug log URL first (the update comes from the same PC)")
        Thread {
            val result: String = try {
                val info = fetchInfo(base)
                Log.i(TAG, "Server has v${info.versionName} (${info.versionCode}); installed v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                when {
                    info.versionCode <= BuildConfig.VERSION_CODE -> "You already have the latest version (v${BuildConfig.VERSION_NAME})"
                    !activity.packageManager.canRequestPackageInstalls() -> {
                        Log.i(TAG, "Install-unknown-apps permission missing; opening settings")
                        main.post {
                            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                        }
                        "Allow “Install unknown apps” for Speaker, come back, and tap Update again (one time only)"
                    }
                    else -> {
                        install(activity, base, info)
                        "Downloaded v${info.versionName}. Tap Update on the next screen"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update failed", e)
                "Update failed: ${e.message}. Is node tools\\log-server.mjs running (restarted after this build)?"
            }
            main.post { done(result) }
        }.start()
    }

    private fun install(ctx: Context, base: String, info: Info) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (info.size > 0) setSize(info.size)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        Log.i(TAG, "Install session $sessionId created; downloading APK")
        installer.openSession(sessionId).use { session ->
            val conn = open("$base/update/apk")
            try {
                if (conn.responseCode !in 200..299) throw IllegalStateException("APK download answered HTTP ${conn.responseCode}")
                var bytes = 0L
                session.openWrite("speaker.apk", 0, info.size).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            bytes += n
                        }
                    }
                    session.fsync(out)
                }
                Log.i(TAG, "Downloaded $bytes bytes; committing install session")
            } finally {
                conn.disconnect()
            }
            val callback = PendingIntent.getBroadcast(
                ctx, sessionId, Intent(ctx, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(callback.intentSender)
        }
    }
}

/** Receives PackageInstaller status: shows Android's confirmation screen, or reports the result. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i("Speaker.update", "Installer status=$status message=$message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("Speaker.update", "Update installed")
            else -> Toast.makeText(ctx, "Update not installed: ${message ?: "status $status"}", Toast.LENGTH_LONG).show()
        }
    }
}

/** After an update, reopen Speaker so the bubble can be started again with one tap. */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i("Speaker.update", "App updated to v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); reopening")
        Toast.makeText(ctx, "Speaker updated to v${BuildConfig.VERSION_NAME}. Tap Start floating bubble", Toast.LENGTH_LONG).show()
        runCatching {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w("Speaker.update", "Could not reopen app after update: $it") }
    }
}
