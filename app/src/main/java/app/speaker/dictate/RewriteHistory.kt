package app.speaker.dictate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last few AI rewrites ("you said → AI wrote"), for the Recent dictations screen.
 * Stored only on this phone, in app-private storage that is excluded from backup and device
 * transfer (see data_extraction_rules.xml). Nothing here is ever sent anywhere.
 */
object RewriteHistory {
    private const val MAX = 50
    private const val FILE = "rewrite_history"

    data class Entry(val time: Long, val spoken: String, val rewritten: String?, val note: String?)

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun add(ctx: Context, spoken: String, rewritten: String?, note: String?) {
        if (!Prefs(ctx).keepHistory) return
        val items = load(ctx).toMutableList()
        items.add(0, Entry(System.currentTimeMillis(), spoken, rewritten, note))
        val arr = JSONArray()
        items.take(MAX).forEach { e ->
            arr.put(JSONObject().put("t", e.time).put("s", e.spoken)
                .put("r", e.rewritten ?: JSONObject.NULL).put("n", e.note ?: JSONObject.NULL))
        }
        sp(ctx).edit().putString("items", arr.toString()).apply()
    }

    /** Newest first. */
    @Synchronized
    fun load(ctx: Context): List<Entry> = runCatching {
        val arr = JSONArray(sp(ctx).getString("items", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(
                o.optLong("t"), o.optString("s"),
                if (o.isNull("r")) null else o.optString("r"),
                if (o.isNull("n")) null else o.optString("n"),
            )
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun clear(ctx: Context) {
        sp(ctx).edit().clear().apply()
    }
}
