package com.audiosleep.net

import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Downloads an HLS stream (m3u8) into a single local file.
 *
 * Segments are appended byte-for-byte: for MPEG-TS playlists this yields a valid
 * concatenated transport stream that [com.audiosleep.media.AudioExtractor] can demux
 * on Android, so no player-side HLS support is needed.
 *
 * Supported: master playlists (first variant), relative segment URLs, AES-128 segment
 * encryption. Unsupported: SAMPLE-AES, live playlists (treated as VOD — one pass).
 */
object HlsDownloader {

    private val keyRegex = Regex("""#EXT-X-KEY:(.*)""")
    private val attrRegex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    fun download(m3u8Url: String, referer: String?, dst: File, onProgress: (Int) -> Unit) {
        var playlistUrl = m3u8Url
        var text = Net.getText(playlistUrl, referer = referer, ua = Net.UA_MOBILE)

        // Master playlist: follow the first variant stream.
        if (text.contains("#EXT-X-STREAM-INF")) {
            val variant = text.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: throw IOException("播放列表里没有可用的清晰度")
            playlistUrl = resolveUrl(playlistUrl, variant)
            text = Net.getText(playlistUrl, referer = referer, ua = Net.UA_MOBILE)
        }

        val baseUrl = playlistUrl.substringBeforeLast('/') + "/"
        val segments = mutableListOf<String>()
        var keyUrl: String? = null
        var ivHex: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-KEY")) {
                val attrs = parseAttrs(line)
                if (attrs["METHOD"] == "AES-128") {
                    keyUrl = attrs["URI"]?.let { resolveUrl(baseUrl, it) }
                    ivHex = attrs["IV"]?.removePrefix("0x")?.removePrefix("0X")
                }
            } else if (!line.startsWith("#")) {
                segments.add(resolveUrl(baseUrl, line))
            }
        }

        if (segments.isEmpty()) throw IOException("播放列表里没有分片")

        // Fetch the AES key once if the playlist is encrypted.
        var keyBytes: ByteArray? = null
        if (keyUrl != null) {
            keyBytes = fetchBytes(keyUrl, referer)
        }

        if (dst.exists()) dst.delete()
        dst.outputStream().use { out ->
            segments.forEachIndexed { index, segUrl ->
                var data = fetchBytes(segUrl, referer)
                if (keyBytes != null) {
                    data = decryptAes128(data, keyBytes, ivHex, index)
                }
                out.write(data)
                out.flush()
                onProgress(((index + 1) * 100) / segments.size)
            }
        }

        if (dst.length() == 0L) throw IOException("下载到的内容为空")
    }

    private fun fetchBytes(url: String, referer: String?): ByteArray {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", Net.UA_MOBILE)
            .header("Accept", "*/*")
        if (referer != null) b.header("Referer", referer)
        Net.client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("分片下载失败：HTTP ${resp.code}")
            return resp.body?.bytes() ?: throw IOException("分片内容为空")
        }
    }

    private fun decryptAes128(data: ByteArray, key: ByteArray, ivHex: String?, sequence: Int): ByteArray {
        return try {
            val iv = if (!ivHex.isNullOrBlank()) {
                hexToBytes(ivHex)
            } else {
                val ivBytes = ByteArray(16)
                ivBytes[12] = ((sequence shr 24) and 0xFF).toByte()
                ivBytes[13] = ((sequence shr 16) and 0xFF).toByte()
                ivBytes[14] = ((sequence shr 8) and 0xFF).toByte()
                ivBytes[15] = (sequence and 0xFF).toByte()
                ivBytes
            }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(data)
        } catch (e: Exception) {
            throw IOException("分片解密失败：${e.message}")
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.padStart(32, '0')
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun parseAttrs(line: String): Map<String, String> {
        val body = line.substringAfter(":", "")
        val out = mutableMapOf<String, String>()
        attrRegex.findAll(body).forEach {
            val k = it.groupValues[1]
            val v = it.groupValues[2].trim().trim('"')
            out[k] = v
        }
        return out
    }

    private fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        if (ref.startsWith("/")) {
            val schemeEnd = base.indexOf("://")
            val hostEnd = base.indexOf('/', schemeEnd + 3)
            val origin = if (hostEnd > 0) base.substring(0, hostEnd) else base
            return origin + ref
        }
        return base + ref
    }

    /** Small helper used by resolvers that need to read a JSON field safely. */
    fun jsonStr(o: JSONObject, key: String): String = o.optString(key).let { if (it == "null") "" else it }
}
