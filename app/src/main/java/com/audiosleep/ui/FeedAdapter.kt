package com.audiosleep.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.audiosleep.R
import com.audiosleep.data.FeedItem
import com.audiosleep.util.ImageLoader
import java.util.Locale

/**
 * Recommendation feed adapter — a vertical card list (cover, title, stats).
 *
 * Tapping a card opens the content in its own app; the small "缓存音频" pill hands the
 * link to the in-app parser instead.
 */
class FeedAdapter(
    private var items: MutableList<FeedItem>,
    private val onCardClick: (FeedItem) -> Unit,
    private val onOpen: (FeedItem) -> Unit,
    private val onCache: (FeedItem) -> Unit
) : RecyclerView.Adapter<FeedAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val ivCover: ImageView = v.findViewById(R.id.ivCover)
        val tvPlatform: TextView = v.findViewById(R.id.tvPlatform)
        val tvDuration: TextView = v.findViewById(R.id.tvDuration)
        val tvTitle: TextView = v.findViewById(R.id.tvTitle)
        val tvMeta: TextView = v.findViewById(R.id.tvMeta)
        val btnCache: TextView = v.findViewById(R.id.btnCache)
        val btnOpen: TextView = v.findViewById(R.id.btnOpen)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_feed, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tvTitle.text = item.title
        holder.tvPlatform.text = item.platform
        holder.tvDuration.text = formatDuration(item.durationSec)

        val author = item.author.ifBlank { "未知作者" }
        holder.tvMeta.text = if (item.viewCount > 0) {
            "$author · ${formatCount(item.viewCount)} 播放"
        } else {
            "$author · ${item.platform}"
        }

        ImageLoader.load(holder.ivCover, item.coverUrl, referer = item.referer.ifBlank { null })

        holder.itemView.setOnClickListener { onCardClick(item) }
        holder.btnOpen.setOnClickListener { onOpen(item) }
        holder.btnCache.setOnClickListener { onCache(item) }
    }

    fun submit(list: MutableList<FeedItem>) {
        items = list
        notifyDataSetChanged()
    }

    private fun formatDuration(sec: Long): String {
        if (sec <= 0) return ""
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
