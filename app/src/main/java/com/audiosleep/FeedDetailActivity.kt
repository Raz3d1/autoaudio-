package com.audiosleep

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.audiosleep.data.FeedItem
import com.audiosleep.util.ImageLoader
import org.json.JSONObject
import java.util.Locale

/**
 * Detail page for a recommendation.
 *
 * Two actions: open the content in its own app, or hand the link back to the main screen
 * so its audio can be cached locally (the secondary feature).
 */
class FeedDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ITEM = "feed_item"
        /** MainActivity reads this to jump into the parser with the link pre-filled. */
        const val EXTRA_CACHE_URL = "cache_url"
    }

    private lateinit var item: FeedItem

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feed_detail)

        val raw = intent.getStringExtra(EXTRA_ITEM)
        item = try {
            FeedItem.fromJson(JSONObject(raw.orEmpty()))
        } catch (e: Exception) {
            finish()
            return
        }

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        render()

        findViewById<TextView>(R.id.btnOpen).setOnClickListener { openExternal() }
        findViewById<TextView>(R.id.btnCache).setOnClickListener { cacheInApp() }
    }

    private fun render() {
        findViewById<TextView>(R.id.tvTitle).text = item.title
        findViewById<TextView>(R.id.tvPlatform).text = item.platform

        val parts = mutableListOf<String>()
        if (item.author.isNotBlank()) parts.add(item.author)
        if (item.viewCount > 0) parts.add("${formatCount(item.viewCount)} 播放")
        if (item.durationSec > 0) parts.add("时长 ${formatDuration(item.durationSec)}")
        findViewById<TextView>(R.id.tvMeta).text = parts.joinToString(" · ")

        val desc = item.desc.trim().ifBlank { "这个推荐来自「${item.platform}」的本周热榜。" }
        findViewById<TextView>(R.id.tvDesc).text = desc

        findViewById<TextView>(R.id.tvExtra).text =
            "点击「去 App 收听」会在对应的 App 里打开；点「缓存音频」则把音轨存到本地离线听。"

        ImageLoader.load(findViewById(R.id.ivCover), item.coverUrl, referer = item.referer.ifBlank { null })
    }

    private fun openExternal() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(item.externalUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "没有找到能打开这个链接的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cacheInApp() {
        val i = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CACHE_URL, item.externalUrl)
        }
        startActivity(i)
        finish()
    }

    private fun formatDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.CHINA, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.CHINA, "%d:%02d", m, s)
    }

    private fun formatCount(n: Long): String = when {
        n >= 100_000_000 -> String.format(Locale.CHINA, "%.1f亿", n / 100_000_000.0)
        n >= 10_000 -> String.format(Locale.CHINA, "%.1f万", n / 10_000.0)
        else -> n.toString()
    }
}
