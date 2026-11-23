package com.audiosleep.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * Resolver implementation A — long-form video pages.
 *
 * Flow:
 *   1. expand the short share link and extract a video identifier
 *   2. query the public metadata endpoint for title / duration / cover / cid
 *   3. query the play-info endpoint, preferring an audio-only DASH track
 *   4. fall back to the progressive (muxed) stream when no audio track is offered
 *
 * A Referer header is mandatory for both the API and the CDN, otherwise the
 * request is rejected with 403.
 */
class PrimaryResolver : Resolver {

    /** BV ids look like BV1GJ411x7h7 — "BV" followed by ten base58 characters. */
    private val bvIdRegex = Regex("""(BV[0-9A-Za-z]{10})""")
    /** Legacy "av123456" ids. */
    private val avIdRegex = Regex("""av(\d+)""", RegexOption.IGNORE_CASE)

    override fun canHandle(url: String): Boolean =
        url.contains("b23.tv") || url.contains("bilibili.com")

    override suspend fun resolve(url: String): ResolveResult = withContext(Dispatchers.IO) {
        val finalUrl = Net.expand(url)

        val bv = bvIdRegex.find(finalUrl)?.value
        val viewUrl = if (bv != null) {
            "$API_META?bvid=$bv"
        } else {
            val aid = avIdRegex.find(finalUrl)?.groupValues?.get(1)
                ?: throw IOException("链接里没找到视频编号")
            "$API_META?aid=$aid"
        }

        val metaJson = Net.getJson(viewUrl, referer = REFERER)
        val data = metaJson.optJSONObject("data")
            ?: throw IOException("视频信息获取失败（可能已删除或受限）")

        val title = data.optString("title", "Untitled")
        val durationMs = data.optLong("duration", 0L) * 1000L
        val cover = data.optString("pic", "")
        val bvid = data.optString("bvid", bv ?: "")
        var cid = data.optLong("cid", 0L)

        val pages = data.optJSONArray("pages")
        if (cid <= 0L && pages != null && pages.length() > 0) {
            cid = pages.getJSONObject(0).optLong("cid", 0L)
        }
        if (cid <= 0L) throw IOException("没有取到内容编号")

        val playJson = Net.getJson(
            "$API_PLAY?bvid=$bvid&cid=$cid&fnval=16&fnver=0&fourk=0",
            referer = REFERER
        )
        val playData = playJson.optJSONObject("data")
            ?: throw IOException("播放地址获取失败")

        // 1) Prefer an audio-only DASH track — smallest download, best quality.
        val dash = playData.optJSONObject("dash")
        if (dash != null) {
            val audios = dash.optJSONArray("audio")
            if (audios != null && audios.length() > 0) {
                var best: JSONObject? = null
                var bestId = -1
                for (i in 0 until audios.length()) {
                    val a = audios.getJSONObject(i)
                    val id = a.optInt("id", 0)
                    if (id > bestId) {
                        bestId = id
                        best = a
                    }
                }
                val u = best?.let {
                    it.optString("baseUrl").ifBlank { it.optString("base_url") }
                }.orEmpty()
                if (u.isNotBlank()) {
                    return@withContext ResolveResult(
                        title = title,
                        durationMs = durationMs,
                        coverUrl = cover,
                        source = SOURCE_ID,
                        sourceUrl = url,
                        mediaUrl = u,
                        audioOnly = true,
                        referer = REFERER
                    )
                }
            }
        }

        // 2) Fall back to the muxed stream; the audio track is demuxed afterwards.
        val durl = playData.optJSONArray("durl")
        if (durl != null && durl.length() > 0) {
            val u = durl.getJSONObject(0).optString("url")
            if (u.isNotBlank()) {
                return@withContext ResolveResult(
                    title = title,
                    durationMs = durationMs,
                    coverUrl = cover,
                    source = SOURCE_ID,
                    sourceUrl = url,
                    mediaUrl = u,
                    audioOnly = false,
                    referer = REFERER
                )
            }
        }

        throw IOException("没有可用的播放地址（可能是付费或受限内容）")
    }

    private companion object {
        const val SOURCE_ID = "primary"
        const val REFERER = "https://www.bilibili.com/"
        const val API_META = "https://api.bilibili.com/x/web-interface/view"
        const val API_PLAY = "https://api.bilibili.com/x/player/playurl"
    }
}
