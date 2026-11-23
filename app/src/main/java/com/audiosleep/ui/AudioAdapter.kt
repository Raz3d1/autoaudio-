package com.audiosleep.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.audiosleep.R
import com.audiosleep.data.AudioItem
import com.audiosleep.util.Cover
import java.util.Locale

/**
 * Library list adapter — a compact row: cover letter, title, duration/size, delete.
 */
class AudioAdapter(
    private var items: MutableList<AudioItem>,
    private val onClick: (AudioItem) -> Unit,
    private val onDelete: (AudioItem) -> Unit
) : RecyclerView.Adapter<AudioAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvCover: TextView = v.findViewById(R.id.tvCover)
        val tvTitle: TextView = v.findViewById(R.id.tvTitle)
        val tvMeta: TextView = v.findViewById(R.id.tvMeta)
        val btnDelete: ImageView = v.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_audio, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        Cover.apply(holder.tvCover, item.title, 14f)
        holder.tvTitle.text = item.title

        val dur = formatDuration(item.durationMs)
        val size = String.format(Locale.CHINA, "%.1f MB", item.sizeBytes / 1024.0 / 1024.0)
        val progress = if (item.durationMs > 0 && item.progressMs > 5000) {
            val pct = (item.progressMs * 100 / item.durationMs).coerceIn(0, 100)
            " · 已听 $pct%"
        } else {
            ""
        }
        holder.tvMeta.text = "$dur · $size$progress"

        holder.itemView.setOnClickListener { onClick(item) }
        holder.btnDelete.setOnClickListener { onDelete(item) }
    }

    fun submit(list: MutableList<AudioItem>) {
        items = list
        notifyDataSetChanged()
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "时长未知"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.CHINA, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.CHINA, "%d:%02d", m, s)
    }
}
