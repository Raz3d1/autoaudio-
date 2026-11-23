package com.audiosleep.net

/**
 * Outcome of resolving a share link.
 *
 * @param mediaUrl   direct URL of a downloadable stream
 * @param audioOnly  true  → [mediaUrl] already points at an audio-only stream (remux directly)
 *                   false → [mediaUrl] is a video container (demux the audio track)
 * @param referer    Referer header required by the CDN, if any
 */
data class ResolveResult(
    val title: String,
    val durationMs: Long,
    val coverUrl: String,
    val source: String,
    val sourceUrl: String,
    val mediaUrl: String,
    val audioOnly: Boolean,
    val referer: String? = null,
    /** true when [mediaUrl] is an HLS playlist (m3u8) */
    val hls: Boolean = false
)

/**
 * Strategy interface for turning a share link into a [ResolveResult].
 *
 * Adding a new source means implementing this interface and registering the
 * implementation in [Resolvers] — nothing else in the app has to change.
 */
interface Resolver {

    /** Cheap, side-effect free check used to pick an implementation. */
    fun canHandle(url: String): Boolean

    /** Network work; must be called off the main thread. */
    suspend fun resolve(url: String): ResolveResult
}

object Resolvers {

    private val all: List<Resolver> = listOf(
        PrimaryResolver(),
        SecondaryResolver(),
        AudioBookResolver(),
        WebPageResolver()
    )

    fun pick(url: String): Resolver? = all.firstOrNull { it.canHandle(url) }

    /** Human readable label for the source that produced a result. */
    fun labelFor(sourceId: String): String = when (sourceId) {
        "primary" -> "长视频"
        "secondary" -> "短视频"
        "audiobook" -> "有声书"
        "web" -> "网页音频"
        else -> "未知来源"
    }
}
