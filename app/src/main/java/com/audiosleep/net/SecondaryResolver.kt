package com.audiosleep.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * Resolver implementation B — short-video share pages.
 *
 * This source exposes no public API, so the page itself is used as the data source:
 *   1. expand the short link to obtain the item id
 *   2. fetch the share page with a mobile User-Agent
 *   3. read the embedded JSON blob (`_ROUTER_DATA`) that carries the item metadata
 *   4. take the first playable URL from `play_addr.url_list`
 *
 * Because it parses a rendered page rather than a documented API, this is the most
 * fragile part of the project. It is intentionally isolated in a single class: if the
 * page structure changes, only this file needs updating.
 */
class SecondaryResolver : Resolver {

    private val routerDataRegex =
        Regex("""window\._ROUTER_DATA\s*=\s*(\{[\s\S]*?\})\s*</script>""")
    private val itemIdRegex = Regex("""/video/(\d{8,25})""")

    override fun canHandle(url: String): Boolean = url.contains("douyin.com")

    override suspend fun resolve(url: String): ResolveResult = withContext(Dispatchers.IO) {
        val finalUrl = Net.expand(url)
        val itemId = extractItemId(finalUrl)
            ?: throw IOException("链接里没找到视频编号")

        val shareUrl = "${SHARE_BASE}video/$itemId/"
        val pageHtml = try {
            Net.getText(shareUrl, referer = REFERER, ua = Net.UA_MOBILE)
        } catch (e: Exception) {
            throw IOException("打不开分享页面：${e.message}")
        }

        val jsonText = routerDataRegex.find(pageHtml)?.groupValues?.get(1)
            ?: throw IOException("页面结构变了，这条链接暂时解析不了")

        val root = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            throw IOException("页面数据格式异常")
        }

        val loaderData = root.optJSONObject("loaderData")
            ?: throw IOException("页面数据不完整")

        var page: JSONObject? = null
        val keys = loaderData.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.contains("video")) {
                page = loaderData.optJSONObject(k)
                break
            }
        }
        if (page == null) throw IOException("页面里没有视频信息")

        val itemList = page.optJSONObject("videoInfoRes")?.optJSONArray("item_list")
        if (itemList == null || itemList.length() == 0) {
            throw IOException("这条拿不到播放地址（可能是图文或私密内容）")
        }
        val item = itemList.getJSONObject(0)

        val title = item.optString("desc", "").ifBlank { "Untitled $itemId" }
        val durationMs = item.optLong("duration", 0L)
        val video = item.optJSONObject("video")
        val urlList = video?.optJSONObject("play_addr")?.optJSONArray("url_list")
            ?: throw IOException("没有返回播放地址")

        var mediaUrl = ""
        for (i in 0 until urlList.length()) {
            val u = urlList.optString(i, "")
            if (u.isNotBlank()) {
                mediaUrl = u
                break
            }
        }
        if (mediaUrl.isBlank()) throw IOException("播放地址为空")

        // Watermarked variants use a different path segment.
        mediaUrl = mediaUrl.replace("playwm", "play")

        val cover = video.optJSONObject("cover")?.optJSONArray("url_list")?.optString(0, "")
            ?: item.optJSONObject("cover")?.optJSONArray("url_list")?.optString(0, "") ?: ""

        ResolveResult(
            title = title,
            durationMs = durationMs,
            coverUrl = cover,
            source = SOURCE_ID,
            sourceUrl = url,
            mediaUrl = mediaUrl,
            audioOnly = false,
            referer = REFERER
        )
    }

    private fun extractItemId(finalUrl: String): String? {
        itemIdRegex.find(finalUrl)?.groupValues?.get(1)?.let { return it }
        return Regex("""(\d{18,20})""").find(finalUrl)?.value
    }

    private companion object {
        const val SOURCE_ID = "secondary"
        const val REFERER = "https://www.douyin.com/"
        const val SHARE_BASE = "https://www.iesdouyin.com/share/"
    }
}
