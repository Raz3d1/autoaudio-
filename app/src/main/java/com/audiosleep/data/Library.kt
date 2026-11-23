package com.audiosleep.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Local index of downloaded items.
 *
 * The index is a small JSON array kept in SharedPreferences; audio files live in the
 * app-private external directory, so no storage permission is required.
 */
object Library {

    private const val PREF = "library"
    private const val KEY = "items"

    fun audioDir(ctx: Context): File {
        val d = File(ctx.getExternalFilesDir(null), "audio")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun fileOf(ctx: Context, item: AudioItem): File = File(audioDir(ctx), item.fileName)

    fun list(ctx: Context): MutableList<AudioItem> {
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY, null) ?: return mutableListOf()
        val out = mutableListOf<AudioItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val item = AudioItem.fromJson(arr.getJSONObject(i))
                // Skip entries whose file has been removed outside the app.
                if (fileOf(ctx, item).exists()) out.add(item)
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun save(ctx: Context, items: List<AudioItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, item: AudioItem) {
        val items = list(ctx)
        items.add(0, item)
        save(ctx, items)
    }

    fun remove(ctx: Context, item: AudioItem) {
        try {
            fileOf(ctx, item).delete()
        } catch (_: Exception) {
        }
        val items = list(ctx)
        items.removeAll { it.id == item.id }
        save(ctx, items)
    }

    fun updateProgress(ctx: Context, id: String, progressMs: Long) {
        val items = list(ctx)
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items[idx].progressMs = progressMs
            save(ctx, items)
        }
    }
}
