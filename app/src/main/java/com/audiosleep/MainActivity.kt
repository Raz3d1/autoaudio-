package com.audiosleep

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject
import com.audiosleep.data.AudioItem
import com.audiosleep.data.FeedItem
import com.audiosleep.data.HistoryItem
import com.audiosleep.data.HistoryStore
import com.audiosleep.data.Library
import com.audiosleep.media.Downloader
import com.audiosleep.media.PlayerService
import com.audiosleep.net.FeedService
import com.audiosleep.net.ResolveResult
import com.audiosleep.net.Resolvers
import com.audiosleep.ui.AudioAdapter
import com.audiosleep.ui.FeedAdapter
import com.audiosleep.ui.HistoryAdapter
import com.audiosleep.util.Cover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Main screen with four tabs:
 *   发现 — recommendation feed (primary feature)
 *   解析 — paste a link and cache the audio locally (secondary feature)
 *   曲库 — downloaded audio
 *   历史 — parse/download history
 */
class MainActivity : AppCompatActivity() {

    private lateinit var pageFeed: View
    private lateinit var pageHome: View
    private lateinit var pageLibrary: View
    private lateinit var pageHistory: View

    private lateinit var navFeed: LinearLayout
    private lateinit var navParse: LinearLayout
    private lateinit var navLibrary: LinearLayout
    private lateinit var navHistory: LinearLayout
    private lateinit var icNavFeed: ImageView
    private lateinit var icNavParse: ImageView
    private lateinit var icNavLibrary: ImageView
    private lateinit var icNavHistory: ImageView
    private lateinit var tvNavFeed: TextView
    private lateinit var tvNavParse: TextView
    private lateinit var tvNavLibrary: TextView
    private lateinit var tvNavHistory: TextView

    private lateinit var chipRow: LinearLayout
    private lateinit var swipeFeed: SwipeRefreshLayout
    private lateinit var rvFeed: RecyclerView
    private lateinit var feedState: View
    private lateinit var feedProgress: View
    private lateinit var tvFeedState: TextView
    private lateinit var btnRefreshFeed: ImageView
    private lateinit var btnTheme: ImageView

    private lateinit var etLink: EditText
    private lateinit var btnParse: TextView
    private lateinit var cardResult: LinearLayout
    private lateinit var tvResCover: TextView
    private lateinit var tvResTitle: TextView
    private lateinit var tvResMeta: TextView
    private lateinit var btnDownload: TextView
    private lateinit var tvStatus: TextView

    private lateinit var rvLibrary: RecyclerView
    private lateinit var emptyLibrary: View
    private lateinit var tvLibraryCount: TextView

    private lateinit var rvHistory: RecyclerView
    private lateinit var emptyHistory: View
    private lateinit var btnClearHistory: TextView

    private lateinit var miniPlayer: LinearLayout
    private lateinit var tvMiniCover: TextView
    private lateinit var tvMiniTitle: TextView
    private lateinit var tvMiniMeta: TextView
    private lateinit var btnMiniPlay: ImageView

    private lateinit var feedAdapter: FeedAdapter
    private lateinit var libraryAdapter: AudioAdapter
    private lateinit var historyAdapter: HistoryAdapter

    private var feedItems: MutableList<FeedItem> = mutableListOf()
    private var items: MutableList<AudioItem> = mutableListOf()
    private var historyItems: MutableList<HistoryItem> = mutableListOf()
    private var currentResult: ResolveResult? = null
    private var currentTab = TAB_FEED
    private var currentCategory = "all"
    private var feedLoadedOnce = false
    private var feedJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val hasItem = intent?.getBooleanExtra(PlayerService.EXTRA_HAS_ITEM, false) ?: false
            val title = intent?.getStringExtra(PlayerService.EXTRA_TITLE).orEmpty()
            val playing = intent?.getBooleanExtra(PlayerService.EXTRA_PLAYING, false) ?: false
            val pos = intent?.getLongExtra(PlayerService.EXTRA_POSITION, 0L) ?: 0L
            val dur = intent?.getLongExtra(PlayerService.EXTRA_DURATION, 0L) ?: 0L
            bindMiniPlayer(hasItem, title, playing, pos, dur)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupTabs()
        setupFeed()
        setupLibrary()
        setupHistory()
        setupActions()
        refreshLibrary()
        refreshHistory()
        askNotificationPermission()
        restorePlayerState()
        handleIncomingIntent(intent)
    }

    // ------------------------------------------------------------ setup

    private fun bindViews() {
        pageFeed = findViewById(R.id.pageFeed)
        pageHome = findViewById(R.id.pageHome)
        pageLibrary = findViewById(R.id.pageLibrary)
        pageHistory = findViewById(R.id.pageHistory)

        navFeed = findViewById(R.id.navFeed)
        navParse = findViewById(R.id.navParse)
        navLibrary = findViewById(R.id.navLibrary)
        navHistory = findViewById(R.id.navHistory)
        icNavFeed = findViewById(R.id.icNavFeed)
        icNavParse = findViewById(R.id.icNavParse)
        icNavLibrary = findViewById(R.id.icNavLibrary)
        icNavHistory = findViewById(R.id.icNavHistory)
        tvNavFeed = findViewById(R.id.tvNavFeed)
        tvNavParse = findViewById(R.id.tvNavParse)
        tvNavLibrary = findViewById(R.id.tvNavLibrary)
        tvNavHistory = findViewById(R.id.tvNavHistory)

        chipRow = findViewById(R.id.chipRow)
        swipeFeed = findViewById(R.id.swipeFeed)
        rvFeed = findViewById(R.id.rvFeed)
        feedState = findViewById(R.id.feedState)
        feedProgress = findViewById(R.id.feedProgress)
        tvFeedState = findViewById(R.id.tvFeedState)
        btnRefreshFeed = findViewById(R.id.btnRefreshFeed)
        btnTheme = findViewById(R.id.btnTheme)

        etLink = findViewById(R.id.etLink)
        btnParse = findViewById(R.id.btnParse)
        cardResult = findViewById(R.id.cardResult)
        tvResCover = findViewById(R.id.tvResCover)
        tvResTitle = findViewById(R.id.tvResTitle)
        tvResMeta = findViewById(R.id.tvResMeta)
        btnDownload = findViewById(R.id.btnDownload)
        tvStatus = findViewById(R.id.tvStatus)

        rvLibrary = findViewById(R.id.rvLibrary)
        emptyLibrary = findViewById(R.id.emptyLibrary)
        tvLibraryCount = findViewById(R.id.tvLibraryCount)

        rvHistory = findViewById(R.id.rvHistory)
        emptyHistory = findViewById(R.id.emptyHistory)
        btnClearHistory = findViewById(R.id.btnClearHistory)

        miniPlayer = findViewById(R.id.miniPlayer)
        tvMiniCover = findViewById(R.id.tvMiniCover)
        tvMiniTitle = findViewById(R.id.tvMiniTitle)
        tvMiniMeta = findViewById(R.id.tvMiniMeta)
        btnMiniPlay = findViewById(R.id.btnMiniPlay)
    }

    private fun setupTabs() {
        navFeed.setOnClickListener { showTab(TAB_FEED) }
        navParse.setOnClickListener { showTab(TAB_PARSE) }
        navLibrary.setOnClickListener { showTab(TAB_LIBRARY) }
        navHistory.setOnClickListener { showTab(TAB_HISTORY) }
        showTab(TAB_FEED)
    }

    private fun showTab(tab: Int) {
        currentTab = tab
        pageFeed.visibility = if (tab == TAB_FEED) View.VISIBLE else View.GONE
        pageHome.visibility = if (tab == TAB_PARSE) View.VISIBLE else View.GONE
        pageLibrary.visibility = if (tab == TAB_LIBRARY) View.VISIBLE else View.GONE
        pageHistory.visibility = if (tab == TAB_HISTORY) View.VISIBLE else View.GONE

        val on = ContextCompat.getColor(this, R.color.accent)
        val off = ContextCompat.getColor(this, R.color.text_tertiary)

        icNavFeed.setColorFilter(if (tab == TAB_FEED) on else off)
        tvNavFeed.setTextColor(if (tab == TAB_FEED) on else off)
        icNavParse.setColorFilter(if (tab == TAB_PARSE) on else off)
        tvNavParse.setTextColor(if (tab == TAB_PARSE) on else off)
        icNavLibrary.setColorFilter(if (tab == TAB_LIBRARY) on else off)
        tvNavLibrary.setTextColor(if (tab == TAB_LIBRARY) on else off)
        icNavHistory.setColorFilter(if (tab == TAB_HISTORY) on else off)
        tvNavHistory.setTextColor(if (tab == TAB_HISTORY) on else off)

        if (tab == TAB_FEED && !feedLoadedOnce) loadFeed(false)
        if (tab == TAB_LIBRARY) refreshLibrary()
        if (tab == TAB_HISTORY) refreshHistory()
    }

    // ------------------------------------------------------------ feed

    private fun setupFeed() {
        feedAdapter = FeedAdapter(
            feedItems,
            onCardClick = { item -> openDetail(item) },
            onOpen = { item -> openExternal(item) },
            onCache = { item -> cacheFromFeed(item) }
        )
        rvFeed.layoutManager = LinearLayoutManager(this)
        rvFeed.adapter = feedAdapter

        btnRefreshFeed.setOnClickListener { loadFeed(true) }
        btnTheme.setOnClickListener { toggleTheme() }
        updateThemeIcon()
        swipeFeed.setColorSchemeColors(ContextCompat.getColor(this, R.color.accent))
        swipeFeed.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.surface_alt))
        swipeFeed.setOnRefreshListener { loadFeed(true) }
        buildChips()
        // Start loading immediately: the feed is the primary screen.
        loadFeed(false)
    }

    /** Switches between the dark and light palettes and recreates the activity. */
    private fun toggleTheme() {
        val prefs = getSharedPreferences(App.PREF_UI, MODE_PRIVATE)
        val night = prefs.getBoolean(App.KEY_NIGHT, true)
        val next = !night
        prefs.edit().putBoolean(App.KEY_NIGHT, next).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (next) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    /** Shows the mode you would switch to: sun while dark, moon while light. */
    private fun updateThemeIcon() {
        val night = getSharedPreferences(App.PREF_UI, MODE_PRIVATE)
            .getBoolean(App.KEY_NIGHT, true)
        btnTheme.setImageResource(if (night) R.drawable.ic_sun else R.drawable.ic_moon)
        btnTheme.contentDescription = if (night) "切换到白天模式" else "切换到夜间模式"
    }

    private fun buildChips() {
        chipRow.removeAllViews()
        val density = resources.displayMetrics.density
        FeedService.CATEGORIES.forEach { cat ->
            val tv = TextView(this)
            tv.text = cat.label
            tv.textSize = 13f
            tv.setPadding(
                (14 * density).toInt(), (7 * density).toInt(),
                (14 * density).toInt(), (7 * density).toInt()
            )
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (8 * density).toInt()
            tv.layoutParams = lp
            tv.setOnClickListener {
                if (currentCategory == cat.id) return@setOnClickListener
                currentCategory = cat.id
                styleChips()
                loadFeed(false)
            }
            chipRow.addView(tv)
        }
        styleChips()
    }

    private fun styleChips() {
        val accent = ContextCompat.getColor(this, R.color.accent)
        val surface = ContextCompat.getColor(this, R.color.surface_alt)
        val onAccent = ContextCompat.getColor(this, R.color.white)
        val secondary = ContextCompat.getColor(this, R.color.text_secondary)

        for (i in 0 until chipRow.childCount) {
            val tv = chipRow.getChildAt(i) as TextView
            val cat = FeedService.CATEGORIES[i]
            val selected = cat.id == currentCategory
            tv.background = ContextCompat.getDrawable(
                this, if (selected) R.drawable.bg_pill_accent else R.drawable.bg_pill
            )
            tv.setTextColor(if (selected) onAccent else secondary)
        }
    }

    private fun loadFeed(force: Boolean) {
        val category = currentCategory
        // Cancelling the previous request keeps fast category switches responsive and
        // prevents a stale response from overwriting the newer category.
        feedJob?.cancel()

        if (force || feedItems.isEmpty()) {
            feedState.visibility = View.VISIBLE
            feedProgress.visibility = View.VISIBLE
            tvFeedState.text = "正在获取本周好评…"
        }

        feedJob = scope.launch {
            val list = withContext(Dispatchers.IO) {
                try {
                    FeedService.load(this@MainActivity, category, force)
                } catch (e: Exception) {
                    emptyList<FeedItem>()
                }
            }
            if (isFinishing || isDestroyed) return@launch
            if (currentCategory != category) return@launch

            feedItems = list.toMutableList()
            feedAdapter.submit(feedItems)
            feedLoadedOnce = feedItems.isNotEmpty()

            swipeFeed.isRefreshing = false
            if (feedItems.isEmpty()) {
                feedState.visibility = View.VISIBLE
                feedProgress.visibility = View.GONE
                tvFeedState.text = "暂时没能获取到推荐，点右上角刷新试试"
            } else {
                feedState.visibility = View.GONE
            }
        }
    }

    private fun openExternal(item: FeedItem) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.externalUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            // No browser or app able to handle it: fall back to the in-app parser.
            Toast.makeText(this, "没有找到能打开这个链接的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDetail(item: FeedItem) {
        startActivity(
            Intent(this, FeedDetailActivity::class.java)
                .putExtra(FeedDetailActivity.EXTRA_ITEM, item.toJson().toString())
        )
    }

    private fun cacheFromFeed(item: FeedItem) {
        showTab(TAB_PARSE)
        etLink.setText(item.externalUrl)
        doParse()
    }

    // ------------------------------------------------------------ library / history

    private fun setupLibrary() {
        libraryAdapter = AudioAdapter(
            items,
            onClick = { item -> playItem(item) },
            onDelete = { item -> confirmDelete(item) }
        )
        rvLibrary.layoutManager = LinearLayoutManager(this)
        rvLibrary.adapter = libraryAdapter
    }

    private fun setupHistory() {
        historyAdapter = HistoryAdapter(
            historyItems,
            onClick = { h ->
                etLink.setText(h.url)
                showTab(TAB_PARSE)
                doParse()
            }
        )
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = historyAdapter
    }

    private fun setupActions() {
        btnParse.setOnClickListener { doParse() }
        btnDownload.setOnClickListener { doDownload() }
        btnClearHistory.setOnClickListener { confirmClearHistory() }
        btnMiniPlay.setOnClickListener { sendControl(PlayerService.ACTION_TOGGLE) }
        miniPlayer.setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java))
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
                )
            }
        }
    }

    private fun restorePlayerState() {
        bindMiniPlayer(
            PlayerService.lastHasItem,
            PlayerService.lastTitle,
            PlayerService.lastPlaying,
            PlayerService.lastPosition,
            PlayerService.lastDuration
        )
    }

    private fun handleIncomingIntent(intent: Intent?) {
        // Coming back from the detail page with "缓存音频".
        val cacheUrl = intent?.getStringExtra(FeedDetailActivity.EXTRA_CACHE_URL)
        if (!cacheUrl.isNullOrBlank()) {
            showTab(TAB_PARSE)
            etLink.setText(cacheUrl)
            doParse()
            return
        }
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if (text.isNotBlank()) {
                etLink.setText(text.trim())
                showTab(TAB_PARSE)
                setStatus("已从分享内容中取到链接，点「解析」")
            }
        }
    }

    // ------------------------------------------------------------ parse

    private fun doParse() {
        val raw = etLink.text.toString().trim()
        if (raw.isEmpty()) {
            setStatus("请先粘贴分享链接")
            return
        }
        val url = Regex("https?://[^\\s，,。]+").find(raw)?.value ?: raw

        val resolver = Resolvers.pick(url)
        if (resolver == null) {
            setStatus("暂时不支持这种链接格式")
            HistoryStore.track(
                this, url.take(40), url, "unknown",
                HistoryItem.STATUS_FAILED, "不支持的链接格式"
            )
            refreshHistory()
            return
        }

        cardResult.visibility = View.GONE
        btnParse.isEnabled = false
        hideKeyboard()
        setStatus("正在解析…")

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { resolver.resolve(url) }
                currentResult = result
                showResult(result)
                HistoryStore.track(
                    this@MainActivity,
                    title = result.title,
                    url = result.sourceUrl,
                    source = Resolvers.labelFor(result.source),
                    status = HistoryItem.STATUS_PARSED,
                    message = "已解析，可下载",
                    coverUrl = result.coverUrl,
                    durationMs = result.durationMs
                )
                refreshHistory()
                setStatus("解析成功，点「下载音频」")
            } catch (e: Exception) {
                currentResult = null
                val msg = e.message ?: "未知错误"
                setStatus("解析失败：$msg")
                HistoryStore.track(
                    this@MainActivity,
                    title = "解析失败：${raw.take(28)}",
                    url = url,
                    source = "未识别",
                    status = HistoryItem.STATUS_FAILED,
                    message = msg
                )
                refreshHistory()
            } finally {
                btnParse.isEnabled = true
            }
        }
    }

    private fun showResult(r: ResolveResult) {
        tvResTitle.text = r.title
        Cover.apply(tvResCover, r.title, 14f)
        val dur = formatDuration(r.durationMs)
        val kind = if (r.audioOnly) "纯音频流" else "视频流 · 自动提取音轨"
        tvResMeta.text = "${Resolvers.labelFor(r.source)} · $dur · $kind"
        cardResult.visibility = View.VISIBLE
        cardResult.alpha = 0f
        cardResult.translationY = 16f * resources.displayMetrics.density
        cardResult.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .start()
    }

    // ------------------------------------------------------------ download

    private fun doDownload() {
        val r = currentResult ?: run {
            setStatus("请先解析链接")
            return
        }
        btnDownload.isEnabled = false
        setStatus("下载中 0%…")

        scope.launch {
            try {
                val item = Downloader.download(this@MainActivity, r) { pct ->
                    setStatus("下载中 $pct%…")
                }
                HistoryStore.markDownloaded(this@MainActivity, r.sourceUrl)
                refreshLibrary()
                refreshHistory()
                setStatus("已保存：${item.title}")
                Toast.makeText(
                    this@MainActivity,
                    String.format(
                        Locale.CHINA, "已保存 %.1f MB",
                        item.sizeBytes / 1024.0 / 1024.0
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                setStatus("下载失败：${e.message ?: "未知错误"}")
            } finally {
                btnDownload.isEnabled = true
            }
        }
    }

    private fun refreshLibrary() {
        items = Library.list(this)
        libraryAdapter.submit(items)
        emptyLibrary.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        tvLibraryCount.text = if (items.isEmpty()) "" else "共 ${items.size} 首"
    }

    private fun refreshHistory() {
        historyItems = HistoryStore.list(this)
        historyAdapter.submit(historyItems)
        emptyHistory.visibility = if (historyItems.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmDelete(item: AudioItem) {
        AlertDialog.Builder(this)
            .setTitle("删除这条音频？")
            .setMessage(item.title)
            .setPositiveButton("删除") { _, _ ->
                Library.remove(this, item)
                refreshLibrary()
                setStatus("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearHistory() {
        if (historyItems.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("清空历史记录？")
            .setMessage("只清空记录，已下载的音频不受影响")
            .setPositiveButton("清空") { _, _ ->
                HistoryStore.clear(this)
                refreshHistory()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ playback

    private fun playItem(item: AudioItem) {
        val i = Intent(this, PlayerService::class.java).apply {
            action = PlayerService.ACTION_PLAY
            putExtra(PlayerService.EXTRA_ID, item.id)
        }
        ContextCompat.startForegroundService(this, i)
        bindMiniPlayer(true, item.title, true, item.progressMs, item.durationMs)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    /**
     * Control commands use plain startService: the activity is in the foreground when
     * they are issued, and they must not trip the startForegroundService watchdog.
     */
    private fun sendControl(action: String) {
        startService(Intent(this, PlayerService::class.java).setAction(action))
    }

    private fun bindMiniPlayer(hasItem: Boolean, title: String, playing: Boolean, pos: Long, dur: Long) {
        if (!hasItem) {
            miniPlayer.visibility = View.GONE
            return
        }
        miniPlayer.visibility = View.VISIBLE
        Cover.apply(tvMiniCover, title, 14f)
        tvMiniTitle.text = title
        tvMiniMeta.text = "${formatDuration(pos)} / ${formatDuration(dur)} · ${if (playing) "播放中" else "已暂停"}"
        btnMiniPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        btnMiniPlay.contentDescription = if (playing) "暂停" else "播放"
        btnMiniPlay.setColorFilter(0xFFFFFFFF.toInt())
    }

    // ------------------------------------------------------------ misc

    private fun setStatus(msg: String) {
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = msg
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etLink.windowToken, 0)
            etLink.clearFocus()
        } catch (_: Exception) {
        }
    }

    private fun formatDuration(ms: Long): String {
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
            this, stateReceiver, IntentFilter(PlayerService.STATE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        restorePlayerState()
    }

    override fun onStop() {
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAB_FEED = 0
        const val TAB_PARSE = 1
        const val TAB_LIBRARY = 2
        const val TAB_HISTORY = 3
    }
}
