package com.audiosleep.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Resolver implementation C — long-form audio share links (audiobooks, podcasts).
 *
 * Flow:
 *   1. expand the short share link and extract the numeric track id
 *   2. query the public track metadata endpoint (title, duration, cover, pay flag)
 *   3. use the ready-made audio-only stream address when the track is free
 *
 * Paid tracks expose no stream address; that is reported explicitly to the user
 * instead of failing silently.
 */
class AudioBookResolver : Resolver {

    private val idPatterns = listOf(
        Regex("""/sound/(\d{6,20})"""),
        Regex("""/track/(\d{6,20})"""),
        Regex("""soundId=(\d{6,20})"""),
        Regex("""trackId=(\d{6,20})""")
    )

    override fun canHandle(url: String): Boolean =
        url.contains("xima.tv") || url.contains("ximalaya.com")

    override suspend fun resolve(url: String): ResolveResult = withContext(Dispatchers.IO) {
        val finalUrl = Net.expand(url)

        val trackId = idPatterns.firstNotNullOfOrNull { it.find(finalUrl)?.groupValues?.get(1) }
            ?: throw IOException("链接里没找到音频编号")

        val meta = Net.getJson("$API_TRACK/$trackId.json", referer = REFERER, ua = Net.UA_MOBILE)

        val title = meta.str("title").ifBlank { "Untitled" }
        val durationSec = meta.optLong("duration", 0L)
        val coverRaw = meta.str("cover_url_142").ifBlank { meta.str("cover_url") }
        val cover = if (coverRaw.startsWith("//")) "https:$coverRaw" else coverRaw

        // Paid / member-only tracks return JSON null here; optString would turn that
        // into the literal string "null", so use the null-safe helper.
        val streamUrl = meta.firstStr("play_path_64", "play_path_32", "play_path")
            .ifBlank { throw IOException("这条是付费/会员内容，拿不到播放地址") }

        ResolveResult(
            title = title,
            durationMs = durationSec * 1000L,
            coverUrl = cover,
            source = SOURCE_ID,
            sourceUrl = url,
            mediaUrl = streamUrl,
            audioOnly = true,
            referer = REFERER
        )
    }

    private companion object {
        const val SOURCE_ID = "audiobook"
        const val REFERER = "https://m.ximalaya.com/"
        const val API_TRACK = "https://m.ximalaya.com/tracks"
    }
}
