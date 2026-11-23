package com.audiosleep.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin HTTP layer shared by every resolver.
 *
 * Responsibilities:
 *  - one configured OkHttp client (timeouts, redirects)
 *  - short-link expansion (follows the redirect chain and returns the final URL)
 *  - JSON / text GET helpers with a configurable User-Agent and Referer
 *  - streaming download with progress callbacks
 *
 * A Referer is required by some media CDNs; resolvers pass it through
 * [ResolveResult.referer] instead of hard-coding it here.
 */
object Net {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    const val UA_MOBILE =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    /** Follows redirects and returns the final URL. */
    fun expand(url: String): String {
        val req = Request.Builder()
            .url(url.trim())
            .header("User-Agent", UA_MOBILE)
            .build()
        client.newCall(req).execute().use { resp ->
            return resp.request.url.toString()
        }
    }

    fun getJson(url: String, referer: String? = null, ua: String = UA_DESKTOP): JSONObject {
        return JSONObject(getText(url, referer, ua))
    }

    fun getText(url: String, referer: String? = null, ua: String = UA_MOBILE): String {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/json,application/xhtml+xml,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        if (referer != null) b.header("Referer", referer)
        client.newCall(b.build()).execute().use { resp ->
            val text = resp.body?.string() ?: throw IOException("服务器返回为空")
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${text.take(120)}")
            return text
        }
    }

    /** Downloads [url] into [dst], reporting 0..100 progress. */
    fun download(url: String, dst: File, referer: String?, onProgress: (Int) -> Unit) {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", UA_DESKTOP)
            .header("Accept", "*/*")
        if (referer != null) b.header("Referer", referer)
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载失败：HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("下载内容为空")
            val total = body.contentLength()
            body.byteStream().use { input ->
                dst.outputStream().use { output ->
                    val buf = ByteArray(256 * 1024)
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    output.flush()
                }
            }
        }
        if (dst.length() == 0L) throw IOException("下载到的文件是空的")
    }
}
