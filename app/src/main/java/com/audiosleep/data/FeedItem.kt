package com.audiosleep.data

import org.json.JSONObject

/**
 * One recommendation shown in the discover feed.
 *
 * @param externalUrl link that opens the content in its own app (or a browser)
 * @param referer     Referer required to load [coverUrl] from the platform's CDN
 * @param durationSec used for the "听书" filter and the duration badge
 */
data class FeedItem(
    val id: String,
    val title: String,
    val category: String,
    val platform: String,
    val externalUrl: String,
    val coverUrl: String,
    val author: String,
    val viewCount: Long,
    val likeCount: Long,
    val durationSec: Long,
    val referer: String = "",
    val desc: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("category", category)
        put("platform", platform)
        put("externalUrl", externalUrl)
        put("coverUrl", coverUrl)
        put("author", author)
        put("viewCount", viewCount)
        put("likeCount", likeCount)
        put("durationSec", durationSec)
        put("referer", referer)
        put("desc", desc)
    }

    companion object {
        fun fromJson(o: JSONObject) = FeedItem(
            id = o.optString("id"),
            title = o.optString("title"),
            category = o.optString("category"),
            platform = o.optString("platform"),
            externalUrl = o.optString("externalUrl"),
            coverUrl = o.optString("coverUrl"),
            author = o.optString("author"),
            viewCount = o.optLong("viewCount"),
            likeCount = o.optLong("likeCount"),
            durationSec = o.optLong("durationSec"),
            referer = o.optString("referer"),
            desc = o.optString("desc")
        )
    }
}
