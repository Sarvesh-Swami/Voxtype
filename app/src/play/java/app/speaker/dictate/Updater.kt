package app.speaker.dictate

import android.app.Activity
import android.content.Context

/**
 * Play flavor: no self-update. Google Play delivers updates; the app never downloads or installs
 * code itself (Play "Device and Network Abuse" policy). Same API as the dev Updater so shared code
 * compiles; the Update button is hidden in this flavor.
 */
object Updater {
    data class Info(val versionCode: Int, val versionName: String, val size: Long)

    fun check(ctx: Context, done: (Info?) -> Unit) = Unit

    fun checkAndInstall(activity: Activity, done: (String) -> Unit) = done("Updates come from Google Play")
}
