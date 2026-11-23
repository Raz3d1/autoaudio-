package com.audiosleep.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.audiosleep.MainActivity
import com.audiosleep.R
import com.audiosleep.data.AudioItem
import com.audiosleep.data.Library

/**
 * Foreground playback service.
 *
 * Owns the single [MediaPlayer] instance, keeps playback alive across screen-off and
 * app switches, exposes transport controls in the notification, and implements the
 * sleep timer.
 *
 * Note on Android 12+: a service started through `startForegroundService` must post a
 * notification within five seconds. Playback therefore starts the foreground state
 * explicitly, and control-only intents post a placeholder notification before handling
 * any work, so the service never trips that watchdog.
 */
class PlayerService : Service() {

    companion object {
        const val ACTION_PLAY = "com.audiosleep.action.PLAY"
        const val ACTION_TOGGLE = "com.audiosleep.action.TOGGLE"
        const val ACTION_STOP = "com.audiosleep.action.STOP"
        const val ACTION_SLEEP = "com.audiosleep.action.SLEEP"
        const val ACTION_SEEK = "com.audiosleep.action.SEEK"

        const val EXTRA_ID = "id"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_SEEK_MS = "seek_ms"

        const val STATE_ACTION = "com.audiosleep.PLAYER_STATE"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_HAS_ITEM = "has_item"
        const val EXTRA_SLEEP_ACTIVE = "sleep_active"
        const val EXTRA_POSITION = "position"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_ITEM_ID = "item_id"

        /** In-process snapshot so a newly opened screen can render immediately. */
        @Volatile var lastTitle: String = ""
        @Volatile var lastPlaying: Boolean = false
        @Volatile var lastHasItem: Boolean = false
        @Volatile var lastSleepActive: Boolean = false
        @Volatile var lastPosition: Long = 0L
        @Volatile var lastDuration: Long = 0L
        @Volatile var lastItemId: String = ""

        private const val CHANNEL_ID = "playback"
        private const val NOTIF_ID = 1001
    }

    private var player: MediaPlayer? = null
    private var current: AudioItem? = null
    private val handler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null
    private var progressRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cmd = intent ?: return START_NOT_STICKY
        val action = cmd.action

        if (action == ACTION_PLAY) {
            val id = cmd.getStringExtra(EXTRA_ID)
            if (id != null) playById(id) else stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification())
        when (action) {
            ACTION_TOGGLE -> toggle()
            ACTION_STOP -> stopPlayback()
            ACTION_SLEEP -> setSleep(cmd.getIntExtra(EXTRA_MINUTES, 0))
            ACTION_SEEK -> seekTo(cmd.getLongExtra(EXTRA_SEEK_MS, 0L))
        }

        if (player == null) {
            stopForegroundCompat()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- playback

    private fun playById(id: String) {
        val item = Library.list(this).firstOrNull { it.id == id } ?: return
        val file = Library.fileOf(this, item)
        if (!file.exists()) return

        releasePlayer()
        current = item

        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                seekTo(item.progressMs.toInt().coerceAtLeast(0))
                setOnCompletionListener {
                    saveProgress()
                    stopPlayback()
                }
                start()
            }
        } catch (e: Exception) {
            stopPlayback()
            return
        }

        startForegroundCompat(buildNotification())
        startProgressLoop()
        broadcastState()
    }

    private fun toggle() {
        val p = player ?: return
        try {
            if (p.isPlaying) {
                p.pause()
                saveProgress()
            } else {
                p.start()
            }
        } catch (_: Exception) {
        }
        updateNotification()
        broadcastState()
    }

    private fun seekTo(ms: Long) {
        val p = player ?: return
        try {
            val dur = p.duration.toLong()
            val target = ms.coerceIn(0L, if (dur > 0) dur else 0L)
            p.seekTo(target.toInt())
        } catch (_: Exception) {
        }
        broadcastState()
    }

    private fun stopPlayback() {
        saveProgress()
        releasePlayer()
        stopProgressLoop()
        cancelSleep()
        current = null
        broadcastState()
        stopForegroundCompat()
        stopSelf()
    }

    private fun saveProgress() {
        val p = player ?: return
        val item = current ?: return
        try {
            val pos = p.currentPosition.toLong()
            if (pos > 0 && pos < (item.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)) {
                Library.updateProgress(this, item.id, pos)
            }
        } catch (_: Exception) {
        }
    }

    private fun releasePlayer() {
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    // ---------------------------------------------------------------- sleep timer

    private fun setSleep(minutes: Int) {
        cancelSleep()
        if (minutes <= 0) {
            broadcastState()
            return
        }
        val r = Runnable {
            try {
                player?.pause()
            } catch (_: Exception) {
            }
            broadcastState()
            stopPlayback()
        }
        sleepRunnable = r
        handler.postDelayed(r, minutes * 60_000L)
        broadcastState()
    }

    private fun cancelSleep() {
        sleepRunnable?.let { handler.removeCallbacks(it) }
        sleepRunnable = null
    }

    // ---------------------------------------------------------------- progress loop

    private fun startProgressLoop() {
        stopProgressLoop()
        val r = object : Runnable {
            override fun run() {
                saveProgress()
                broadcastState()
                handler.postDelayed(this, 1000L)
            }
        }
        progressRunnable = r
        handler.postDelayed(r, 1000L)
    }

    private fun stopProgressLoop() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playPause = PendingIntent.getService(
            this, 1, Intent(this, PlayerService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, PlayerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playing = try {
            player?.isPlaying == true
        } catch (_: Exception) {
            false
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(current?.title ?: getString(R.string.app_name))
            .setContentText(if (playing) getString(R.string.state_playing) else getString(R.string.state_paused))
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentIntent(openIntent)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .addAction(0, if (playing) getString(R.string.action_pause) else getString(R.string.action_play), playPause)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // ---------------------------------------------------------------- state broadcast

    private fun broadcastState() {
        val p = player
        val playing = try {
            p?.isPlaying == true
        } catch (_: Exception) {
            false
        }
        val position = try {
            p?.currentPosition?.toLong() ?: 0L
        } catch (_: Exception) {
            0L
        }
        val duration = try {
            p?.duration?.toLong() ?: 0L
        } catch (_: Exception) {
            0L
        }
        val title = current?.title.orEmpty()
        val hasItem = current != null
        val sleepActive = sleepRunnable != null
        val itemId = current?.id.orEmpty()

        lastTitle = title
        lastPlaying = playing
        lastHasItem = hasItem
        lastSleepActive = sleepActive
        lastPosition = position
        lastDuration = duration
        lastItemId = itemId

        val i = Intent(STATE_ACTION).apply {
            setPackage(packageName)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_PLAYING, playing)
            putExtra(EXTRA_HAS_ITEM, hasItem)
            putExtra(EXTRA_SLEEP_ACTIVE, sleepActive)
            putExtra(EXTRA_POSITION, position)
            putExtra(EXTRA_DURATION, duration)
            putExtra(EXTRA_ITEM_ID, itemId)
        }
        sendBroadcast(i)
    }

    override fun onDestroy() {
        saveProgress()
        releasePlayer()
        stopProgressLoop()
        cancelSleep()
        super.onDestroy()
    }
}
