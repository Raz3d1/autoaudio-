package com.audiosleep.util

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView

/**
 * Cover placeholder helpers.
 *
 * No image loading: every item gets a deterministic colour derived from its title,
 * plus the first meaningful character. This is cheap, offline and looks closer to a
 * real cover grid than a single accent colour.
 */
object Cover {

    private val PALETTE = intArrayOf(
        Color.parseColor("#E5484D"),
        Color.parseColor("#E0602C"),
        Color.parseColor("#C8912B"),
        Color.parseColor("#2E9E6B"),
        Color.parseColor("#2C8C9E"),
        Color.parseColor("#3B6FD4"),
        Color.parseColor("#7659D6"),
        Color.parseColor("#C0468C"),
        Color.parseColor("#4F5B6B"),
        Color.parseColor("#8A6A3F")
    )

    /**
     * First meaningful character of a title: skips punctuation and brackets so
     * 《书名》 or 【tag】 prefixes do not become the cover letter.
     */
    fun letter(title: String): String {
        val t = title.trim()
        for (ch in t) {
            if (ch.isLetterOrDigit()) return ch.toString()
        }
        return "音"
    }

    fun colorFor(title: String): Int {
        val key = title.trim().ifEmpty { "音" }
        val idx = ((key.hashCode() % PALETTE.size) + PALETTE.size) % PALETTE.size
        return PALETTE[idx]
    }

    private fun dp(ctx: android.content.Context, value: Float): Float =
        value * ctx.resources.displayMetrics.density

    /** Rounded, colour-coded background for a cover placeholder. */
    fun background(view: View, title: String, radiusDp: Float) {
        val gd = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(view.context, radiusDp)
            setColor(colorFor(title))
        }
        view.background = gd
    }

    fun apply(textView: TextView, title: String, radiusDp: Float) {
        textView.text = letter(title)
        background(textView, title, radiusDp)
    }
}
