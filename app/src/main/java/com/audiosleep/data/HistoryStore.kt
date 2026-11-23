package com.audiosleep.data

import android.content.Context
import org.json.JSONArray
import java.util.UUID

/**
 * Parse/download history, newest first.
 *
 * Kept as a JSON array in SharedPreferences: a few hundred small records written
 * only on user actions, so a database would add no value.
 */
object HistoryStore {

    private const val PREF = "history"
    private const val KEY = "items"
    private const val MAX = 200

    fun list(ctx: Context): MutableList<HistoryItem> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return mutableListOf()
        val out = mutableListOf<HistoryItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) out.add(HistoryItem.fromJson(arr.getJSONObject(i)))
        } catch (_: Exception) {
        }
        return out
    }

    fun save(ctx: Context, items: List<HistoryItem>) {
        val arr = JSONArray()
        items.take(MAX).forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    /** Records a successful or failed parse. Returns the stored entry. */
    fun track(
        ctx: Context,
        title: String,
        url: String,
        source: String,
        status: Int,
        message: String = "",
        coverUrl: String = "",
        durationMs: Long = 0L
    ): HistoryItem {
        val items = list(ctx)
        val item = HistoryItem(
            id = UUID.randomUUID().toString(),
            title = title,
            url = url,
            source = source,
            status = status,
            message = message,
            coverUrl = coverUrl,
            durationMs = durationMs,
            timeMs = System.currentTimeMillis()
        )
        items.add(0, item)
        save(ctx, items.take(MAX))
        return item
    }

    /** Marks the most recent entry for [url] as downloaded. */
    fun markDownloaded(ctx: Context, url: String) {
        val items = list(ctx)
        val idx = items.indexOfFirst { it.url == url }
        if (idx >= 0) {
            val old = items[idx]
            items[idx] = old.copy(status = HistoryItem.STATUS_DOWNLOADED, message = "已保存到本地")
            save(ctx, items)
        }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
