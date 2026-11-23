package com.audiosleep.data

import org.json.JSONObject

/** One entry in the parse/download history. */
data class HistoryItem(
    val id: String,
    val title: String,
    val url: String,
    val source: String,
    /** 0 = parsed only, 1 = downloaded, 2 = failed */
    val status: Int,
    val message: String,
    val coverUrl: String,
    val durationMs: Long,
    val timeMs: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("url", url)
        put("source", source)
        put("status", status)
        put("message", message)
        put("coverUrl", coverUrl)
        put("durationMs", durationMs)
        put("timeMs", timeMs)
    }

    companion object {
        const val STATUS_PARSED = 0
        const val STATUS_DOWNLOADED = 1
        const val STATUS_FAILED = 2

        fun fromJson(o: JSONObject) = HistoryItem(
            id = o.optString("id"),
            title = o.optString("title"),
            url = o.optString("url"),
            source = o.optString("source"),
            status = o.optInt("status", STATUS_PARSED),
            message = o.optString("message"),
            coverUrl = o.optString("coverUrl"),
            durationMs = o.optLong("durationMs"),
            timeMs = o.optLong("timeMs")
        )
    }
}
