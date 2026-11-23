package com.audiosleep.data

import org.json.JSONObject

/**
 * A single downloaded audio item.
 *
 * @param source   identifier of the resolver that produced this item
 * @param sourceUrl the share link the user pasted (kept for reference / re-resolve)
 */
data class AudioItem(
    val id: String,
    val title: String,
    val source: String,
    val sourceUrl: String,
    val durationMs: Long,
    val coverUrl: String,
    val fileName: String,
    val sizeBytes: Long,
    val createdAt: Long,
    var progressMs: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("source", source)
        put("sourceUrl", sourceUrl)
        put("durationMs", durationMs)
        put("coverUrl", coverUrl)
        put("fileName", fileName)
        put("sizeBytes", sizeBytes)
        put("createdAt", createdAt)
        put("progressMs", progressMs)
    }

    companion object {
        fun fromJson(o: JSONObject): AudioItem = AudioItem(
            id = o.optString("id"),
            title = o.optString("title"),
            source = o.optString("source"),
            sourceUrl = o.optString("sourceUrl"),
            durationMs = o.optLong("durationMs"),
            coverUrl = o.optString("coverUrl"),
            fileName = o.optString("fileName"),
            sizeBytes = o.optLong("sizeBytes"),
            createdAt = o.optLong("createdAt"),
            progressMs = o.optLong("progressMs")
        )
    }
}
