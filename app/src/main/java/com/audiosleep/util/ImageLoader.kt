package com.audiosleep.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.audiosleep.net.Net
import okhttp3.Request
import java.util.concurrent.Executors

/**
 * Minimal asynchronous image loader for the recommendation feed.
 *
 * Deliberately tiny: one in-memory LRU cache, a small thread pool, and a tag check so
 * a recycled row never shows the wrong cover. Covers from video CDNs require a Referer,
 * which is sent for every request.
 */
object ImageLoader {

    private val cache = LruCache<String, Bitmap>(96)
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, url: String, referer: String? = null) {
        if (url.isBlank()) return
        val cached = cache.get(url)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }

        view.tag = url
        view.setImageDrawable(null)

        pool.execute {
            try {
                val builder = Request.Builder()
                    .url(url)
                    .header("User-Agent", Net.UA_DESKTOP)
                    .header("Accept", "image/*")
                if (referer != null) builder.header("Referer", referer)
                val resp = Net.client.newCall(builder.build()).execute()
                resp.use {
                    val bytes = it.body?.bytes() ?: return@use
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use
                    cache.put(url, bmp)
                    main.post {
                        if (view.tag == url) view.setImageBitmap(bmp)
                    }
                }
            } catch (_: Exception) {
                // A missing cover just leaves the placeholder colour in place.
            }
        }
    }
}
