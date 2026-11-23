package com.audiosleep

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.audiosleep.media.PlayerService
import com.audiosleep.util.Cover
import java.util.Locale

/**
 * Full-screen player: large artwork placeholder, title, progress with seeking,
 * transport controls and the sleep timer.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvCover: TextView
    private lateinit var tvPosition: TextView
    private lateinit var tvDuration: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageView

    private var durationMs: Long = 0L
    private var seeking = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val i = intent ?: return
            val hasItem = i.getBooleanExtra(PlayerService.EXTRA_HAS_ITEM, false)
            if (!hasItem) {
                finish()
                return
            }
            durationMs = i.getLongExtra(PlayerService.EXTRA_DURATION, 0L)
            render(
                title = i.getStringExtra(PlayerService.EXTRA_TITLE).orEmpty(),
                playing = i.getBooleanExtra(PlayerService.EXTRA_PLAYING, false),
                position = i.getLongExtra(PlayerService.EXTRA_POSITION, 0L),
                duration = durationMs
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        tvTitle = findViewById(R.id.tvPlayerTitle)
        tvMeta = findViewById(R.id.tvPlayerMeta)
        tvCover = findViewById(R.id.tvPlayerCover)
        tvPosition = findViewById(R.id.tvPosition)
        tvDuration = findViewById(R.id.tvDuration)
        seekBar = findViewById(R.id.seekBar)
        btnPlayPause = findViewById(R.id.btnPlayPause)

        findViewById<ImageView>(R.id.btnCollapse).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btnMore).setOnClickListener { showActions() }
        findViewById<ImageView>(R.id.btnStop).setOnClickListener {
            startService(Intent(this, PlayerService::class.java).setAction(PlayerService.ACTION_STOP))
            finish()
        }
        findViewById<ImageView>(R.id.btnTimer).setOnClickListener { showSleepDialog() }

        btnPlayPause.setOnClickListener {
            startService(Intent(this, PlayerService::class.java).setAction(PlayerService.ACTION_TOGGLE))
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvPosition.text = format(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                seeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                seeking = false
                val ms = sb?.progress ?: 0
                startService(
                    Intent(this@PlayerActivity, PlayerService::class.java)
                        .setAction(PlayerService.ACTION_SEEK)
                        .putExtra(PlayerService.EXTRA_SEEK_MS, ms.toLong())
                )
            }
        })

        render(
            title = PlayerService.lastTitle,
            playing = PlayerService.lastPlaying,
            position = PlayerService.lastPosition,
            duration = PlayerService.lastDuration
        )
    }

    private fun render(title: String, playing: Boolean, position: Long, duration: Long) {
        val t = title.ifBlank { "未在播放" }
        tvTitle.text = t
        Cover.apply(tvCover, t, 28f)
        tvMeta.text = if (playing) "正在播放 · 本地音频" else "已暂停 · 本地音频"

        btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)

        if (duration > 0) {
            seekBar.max = duration.toInt()
            if (!seeking) seekBar.progress = position.toInt().coerceIn(0, duration.toInt())
            tvDuration.text = format(duration)
        } else {
            seekBar.max = 100
            if (!seeking) seekBar.progress = 0
            tvDuration.text = "0:00"
        }
        if (!seeking) tvPosition.text = format(position)
    }

    private fun showSleepDialog() {
        val options = arrayOf("15 分钟", "30 分钟", "45 分钟", "60 分钟", "取消定时")
        AlertDialog.Builder(this)
            .setTitle("睡眠定时")
            .setItems(options) { _, which ->
                val minutes = when (which) {
                    0 -> 15
                    1 -> 30
                    2 -> 45
                    3 -> 60
                    else -> 0
                }
                startService(
                    Intent(this, PlayerService::class.java)
                        .setAction(PlayerService.ACTION_SLEEP)
                        .putExtra(PlayerService.EXTRA_MINUTES, minutes)
                )
            }
            .show()
    }

    private fun showActions() {
        val options = arrayOf("睡眠定时", "停止播放")
        AlertDialog.Builder(this)
            .setTitle("更多")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSleepDialog()
                    1 -> {
                        startService(
                            Intent(this, PlayerService::class.java)
                                .setAction(PlayerService.ACTION_STOP)
                        )
                        finish()
                    }
                }
            }
            .show()
    }

    private fun format(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.CHINA, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.CHINA, "%d:%02d", m, s)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(PlayerService.STATE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        super.onStop()
    }
}
