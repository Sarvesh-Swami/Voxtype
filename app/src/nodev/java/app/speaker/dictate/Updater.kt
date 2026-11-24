package app.speaker.dictate

import android.app.Activity
import android.content.Context

/**
 * Default Updater: no self-update. Used by every release (Google Play) build, and by debug builds when the
 * private dev-local/ folder isn't present. Google Play delivers updates; the app never downloads or installs
 * code itself (Play "Device and Network Abuse" policy). The real sideload updater lives only in the
 * git-ignored dev-local/android/ folder and has the same API, so shared code compiles either way.
 * The Update button is hidden whenever BuildConfig.DEV_TOOLS is false.
 */
object Updater {
    data class Info(val versionCode: Int, val versionName: String, val size: Long)

    fun check(ctx: Context, done: (Info?) -> Unit) = Unit

    fun checkAndInstall(activity: Activity, done: (String) -> Unit) = done("Updates come from Google Play")
}
