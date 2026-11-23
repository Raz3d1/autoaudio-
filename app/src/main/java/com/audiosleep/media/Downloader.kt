package com.audiosleep.media

import android.content.Context
import com.audiosleep.data.AudioItem
import com.audiosleep.data.Library
import com.audiosleep.net.HlsDownloader
import com.audiosleep.net.Net
import com.audiosleep.net.ResolveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Turns a [ResolveResult] into a stored [AudioItem].
 *
 * Steps: download the stream to a temp file → demux the audio track into an `.m4a`
 * → append to the library index → drop the temp file.
 */
object Downloader {

    suspend fun download(
        ctx: Context,
        result: ResolveResult,
        onProgress: (Int) -> Unit
    ): AudioItem = withContext(Dispatchers.IO) {

        val tmp = File(ctx.cacheDir, "dl_${System.currentTimeMillis()}.tmp")

        try {
            // 0..90 : download（HLS 播放列表需要先合并分片）
            if (result.hls) {
                HlsDownloader.download(result.mediaUrl, result.referer, tmp) { p ->
                    onProgress((p * 90) / 100)
                }
            } else {
                Net.download(result.mediaUrl, tmp, result.referer) { p ->
                    onProgress((p * 90) / 100)
                }
            }

            val dir = Library.audioDir(ctx)
            val name = UUID.randomUUID().toString() + ".m4a"
            val out = File(dir, name)

            // 90..100 : demux
            onProgress(92)
            var ok = AudioExtractor.extract(tmp, out)
            if (!ok && result.audioOnly) {
                // Already an audio-only container: keep it as-is.
                tmp.copyTo(out, overwrite = true)
                ok = out.length() > 0
            }
            onProgress(98)

            if (!ok || !out.exists() || out.length() == 0L) {
                try {
                    out.delete()
                } catch (_: Exception) {
                }
                throw IOException("无法从这个文件里提取音轨")
            }

            val item = AudioItem(
                id = UUID.randomUUID().toString(),
                title = result.title.ifBlank { "Untitled" },
                source = result.source,
                sourceUrl = result.sourceUrl,
                durationMs = result.durationMs,
                coverUrl = result.coverUrl,
                fileName = name,
                sizeBytes = out.length(),
                createdAt = System.currentTimeMillis()
            )
            Library.add(ctx, item)
            onProgress(100)
            item
        } finally {
            try {
                if (tmp.exists()) tmp.delete()
            } catch (_: Exception) {
            }
        }
    }
}
