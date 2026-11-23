package com.audiosleep.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Resolver implementation D — generic web page fallback.
 *
 * Registered last, so it only handles links the dedicated adapters did not claim.
 * It reads the page the share link points at and looks for a media address that the
 * page itself publishes:
 *
 *   1. Open Graph video tags (`og:video`, `og:video:url`, `og:video:secure_url`)
 *   2. `<video src=...>` / `<audio src=...>`
 *   3. any absolute `.mp4` / `.m4a` / `.mp3` / `.m3u8` URL in the markup
 *
 * Works for many small sites and news pages; sites that render video purely from
 * JavaScript will fail here, and the user gets an explicit message instead of a
 * silent failure.
 */
class WebPageResolver : Resolver {

    private val ogVideo = listOf(
        Regex("""<meta[^>]+property=["']og:video:secure_url["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+property=["']og:video:url["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+property=["']og:video["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+name=["']twitter:player:stream["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE)
    )

    private val ogImage = listOf(
        Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+name=["']twitter:image["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE)
    )

    private val titleRegexes = listOf(
        Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+name=["']twitter:title["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE),
        Regex("""<title[^>]*>([^<]{1,120})</title>""", RegexOption.IGNORE_CASE)
    )

    private val mediaTagRegexes = listOf(
        Regex("""<(?:video|audio)[^>]+src=["']([^"']+)""", RegexOption.IGNORE_CASE),
        Regex("""<(?:video|audio)[^>]+data-src=["']([^"']+)""", RegexOption.IGNORE_CASE),
        Regex("""<source[^>]+src=["']([^"']+)""", RegexOption.IGNORE_CASE)
    )

    private val looseMedia = Regex(
        """https?://[^\s"'\\<>]+?\.(?:m4a|mp4|mp3|m3u8)(?:\?[^\s"'\\<>]*)?""",
        RegexOption.IGNORE_CASE
    )

    override fun canHandle(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    override suspend fun resolve(url: String): ResolveResult = withContext(Dispatchers.IO) {
        val finalUrl = Net.expand(url)
        val referer = originOf(finalUrl)

        val html = try {
            Net.getText(finalUrl, referer = referer, ua = Net.UA_MOBILE)
        } catch (e: Exception) {
            throw IOException("打不开这个页面：${e.message}")
        }

        val mediaUrl = findMediaUrl(html)
            ?: throw IOException("这个页面没有公开的音视频地址（可能需要登录或由脚本动态加载）")

        val title = titleRegexes.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "网页音频"

        val cover = ogImage.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }.orEmpty()

        val durationMs = Regex("""(?:duration|length)["']?\s*[:=]\s*["']?(\d{1,5})(?:\.\d+)?""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.toLongOrNull()
            ?.let { it * 1000L } ?: 0L

        val isHls = mediaUrl.contains(".m3u8", ignoreCase = true)

        ResolveResult(
            title = title,
            durationMs = durationMs,
            coverUrl = cover,
            source = SOURCE_ID,
            sourceUrl = url,
            mediaUrl = mediaUrl,
            audioOnly = !isHls && (mediaUrl.contains(".m4a") || mediaUrl.contains(".mp3")),
            referer = referer,
            hls = isHls
        )
    }

    private fun findMediaUrl(html: String): String? {
        ogVideo.forEach { r ->
            r.find(html)?.groupValues?.get(1)?.let { return it }
        }
        mediaTagRegexes.forEach { r ->
            r.find(html)?.groupValues?.get(1)?.let { return it }
        }
        // Prefer audio-only files over playlists and video.
        val all = looseMedia.findAll(html).map { it.value }.toList()
        return all.firstOrNull { it.contains(".m4a") || it.contains(".mp3") }
            ?: all.firstOrNull { it.contains(".m3u8") }
            ?: all.firstOrNull()
    }

    private fun originOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val hostEnd = url.indexOf('/', schemeEnd + 3)
        return if (hostEnd > 0) url.substring(0, hostEnd) else url
    }

    private companion object {
        const val SOURCE_ID = "web"
    }
}
