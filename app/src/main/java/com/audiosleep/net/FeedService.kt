package com.audiosleep.net

import android.content.Context
import com.audiosleep.data.FeedItem
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Recommendation feed source (multi-platform).
 *
 * Two public rankings are aggregated:
 *   - long-form video popularity ranking  → video / knowledge / audiobook categories
 *   - music chart                         → music category
 *
 * Categories can pull from several sources; results are interleaved so the feed does not
 * look like a single chart. Everything is cached per category for [CACHE_TTL_MS], which
 * also makes the last feed readable offline.
 */
object FeedService {

    private const val PREF = "feed"
    private const val CACHE_TTL_MS = 30 * 60 * 1000L

    private const val REF_BILI = "https://www.bilibili.com/"
    private const val REF_MUSIC = "https://music.163.com/"

    /** Where a category takes its content from. */
    private sealed class Source {
        data class BiliRank(val rid: Int, val limit: Int) : Source()
        data class MusicChart(val playlistId: Long, val limit: Int) : Source()
    }

    data class Category(val id: String, val label: String)

    val CATEGORIES = listOf(
        Category("all", "精选"),
        Category("video", "视频"),
        Category("music", "音乐"),
        Category("audiobook", "听书"),
        Category("knowledge", "知识")
    )

    private fun sourcesOf(categoryId: String): List<Source> = when (categoryId) {
        "all" -> listOf(
            Source.BiliRank(0, 12),
            Source.MusicChart(3778678, 10),
            Source.BiliRank(36, 10)
        )
        "video" -> listOf(Source.BiliRank(0, 40))
        "music" -> listOf(Source.MusicChart(3778678, 30), Source.BiliRank(3, 15))
        "audiobook" -> listOf(Source.BiliRank(36, 40), Source.BiliRank(3, 20))
        "knowledge" -> listOf(Source.BiliRank(36, 40))
        else -> listOf(Source.BiliRank(0, 20))
    }
    fun categoryOf(id: String): Category =
        CATEGORIES.firstOrNull { it.id == id } ?: CATEGORIES[0]

    /** Loads a category, using cache when fresh. Must be called off the main thread. */
    fun load(ctx: Context, categoryId: String, force: Boolean = false): List<FeedItem> {
        val cat = categoryOf(categoryId)
        val sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

        if (!force) {
            val raw = sp.getString("cache_${cat.id}", null)
            val at = sp.getLong("cache_at_${cat.id}", 0L)
            if (raw != null && System.currentTimeMillis() - at < CACHE_TTL_MS) {
                val cached = parseCached(raw)
                if (cached.isNotEmpty()) return cached
            }
        }

        val perSource = mutableListOf<List<FeedItem>>()
        sourcesOf(cat.id).forEach { src ->
            perSource.add(when (src) {
                is Source.BiliRank -> fetchBiliRank(src.rid, cat.id, src.limit)
                is Source.MusicChart -> fetchMusicChart(src.playlistId, cat.id, src.limit)
            })
        }

        var items = interleave(perSource)

        // 接口不可用 / 返回为空时，用打包的编辑精选兜底，保证首页永远有内容
        if (items.isEmpty()) {
            items = loadEditorPicks(ctx)
        }

        // "听书" keeps long-form audio only — the formats that suit bedtime listening.
        if (cat.id == "audiobook") {
            items = items.filter { it.durationSec >= 20 * 60 }
        }

        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        sp.edit()
            .putString("cache_${cat.id}", arr.toString())
            .putLong("cache_at_${cat.id}", System.currentTimeMillis())
            .apply()

        return items
    }

    /** Bundled fallback list, generated from real rankings at build time. */
    private fun loadEditorPicks(ctx: Context): List<FeedItem> = try {
        val raw = ctx.assets.open("editor_picks.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(raw).optJSONArray("items") ?: JSONArray()
        (0 until arr.length()).map { FeedItem.fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun parseCached(raw: String): List<FeedItem> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { FeedItem.fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }

    /** Round-robin over sources so every platform shows up near the top. */
    private fun interleave(groups: List<List<FeedItem>>): List<FeedItem> {
        if (groups.isEmpty()) return emptyList()
        val out = mutableListOf<FeedItem>()
        val seen = mutableSetOf<String>()
        val max = groups.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) {
            groups.forEach { g ->
                if (i < g.size) {
                    val item = g[i]
                    if (seen.add(item.platform + item.id)) out.add(item)
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------ sources

    private fun httpJson(url: String, referer: String): String? {
        val client = Net.client.newBuilder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Net.UA_DESKTOP)
            .header("Referer", referer)
            .header("Accept", "application/json")
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchBiliRank(rid: Int, categoryId: String, limit: Int): List<FeedItem> {
        val body = httpJson(
            "https://api.bilibili.com/x/web-interface/ranking/v2?rid=$rid&type=all",
            REF_BILI
        ) ?: return emptyList()

        return try {
            val list = JSONObject(body).optJSONObject("data")?.optJSONArray("list")
                ?: return emptyList()
            val out = mutableListOf<FeedItem>()
            for (i in 0 until minOf(list.length(), limit)) {
                val it = list.getJSONObject(i)
                val bvid = it.optString("bvid")
                if (bvid.isBlank()) continue
                val stat = it.optJSONObject("stat")
                out.add(
                    FeedItem(
                        id = bvid,
                        title = it.optString("title"),
                        category = categoryId,
                        platform = "B站",
                        externalUrl = "https://www.bilibili.com/video/$bvid",
                        coverUrl = it.optString("pic").replace("http://", "https://"),
                        author = it.optJSONObject("owner")?.optString("name").orEmpty(),
                        viewCount = stat?.optLong("view") ?: 0L,
                        likeCount = stat?.optLong("like") ?: 0L,
                        durationSec = it.optLong("duration"),
                        referer = REF_BILI,
                        desc = it.optString("desc").take(400)
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchMusicChart(playlistId: Long, categoryId: String, limit: Int): List<FeedItem> {
        val body = httpJson(
            "https://music.163.com/api/playlist/detail?id=$playlistId",
            REF_MUSIC
        ) ?: return emptyList()

        return try {
            val result = JSONObject(body).optJSONObject("result") ?: return emptyList()
            val list = result.optJSONArray("tracks") ?: return emptyList()
            val chartName = result.optString("name").ifBlank { "排行榜" }
            val out = mutableListOf<FeedItem>()
            for (i in 0 until minOf(list.length(), limit)) {
                val t = list.getJSONObject(i)
                val id = t.optLong("id")
                if (id <= 0L) continue
                val artists = t.optJSONArray("artists")
                val author = buildString {
                    if (artists != null) {
                        for (k in 0 until minOf(artists.length(), 3)) {
                            if (k > 0) append("/")
                            append(artists.getJSONObject(k).optString("name"))
                        }
                    }
                }
                val album = t.optJSONObject("album")
                val cover = album?.optString("picUrl").orEmpty().replace("http://", "https://")
                out.add(
                    FeedItem(
                        id = "ne$id",
                        title = t.optString("name"),
                        category = categoryId,
                        platform = "网易云",
                        externalUrl = "https://music.163.com/song?id=$id",
                        coverUrl = cover,
                        author = if (author.isBlank()) chartName else author,
                        viewCount = 0L,
                        likeCount = 0L,
                        durationSec = t.optLong("duration") / 1000L,
                        referer = REF_MUSIC,
                        desc = album?.optString("name").orEmpty()
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}
