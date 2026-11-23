package com.audiosleep.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.audiosleep.R
import com.audiosleep.data.HistoryItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * History list adapter: title, status badge and a relative timestamp.
 * Tapping a row re-parses that link.
 */
class HistoryAdapter(
    private var items: MutableList<HistoryItem>,
    private val onClick: (HistoryItem) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTitle: TextView = v.findViewById(R.id.tvHTitle)
        val tvStatus: TextView = v.findViewById(R.id.tvHStatus)
        val tvMeta: TextView = v.findViewById(R.id.tvHMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val h = items[position]
        val ctx = holder.itemView.context

        holder.tvTitle.text = h.title

        val (label, colorRes) = when (h.status) {
            HistoryItem.STATUS_DOWNLOADED -> "已下载" to R.color.accent
            HistoryItem.STATUS_FAILED -> "失败" to R.color.text_tertiary
            else -> "已解析" to R.color.text_secondary
        }
        holder.tvStatus.text = label
        holder.tvStatus.setTextColor(ContextCompat.getColor(ctx, colorRes))

        val time = formatTime(h.timeMs)
        val source = if (h.source.isBlank()) "" else "${h.source} · "
        holder.tvMeta.text = "$source$time"

        holder.itemView.setOnClickListener { onClick(h) }
    }

    fun submit(list: MutableList<HistoryItem>) {
        items = list
        notifyDataSetChanged()
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return ""
        val now = System.currentTimeMillis()
        val diff = now - ms
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000} 分钟前"
            diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
            diff < 7 * 86_400_000L -> "${diff / 86_400_000} 天前"
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ms))
        }
    }
}
