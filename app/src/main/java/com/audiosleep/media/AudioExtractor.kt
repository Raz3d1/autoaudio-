package com.audiosleep.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Demuxes the audio track out of a media container and writes it as a standard
 * `.m4a` (MPEG-4 audio) file.
 *
 * Implemented with the platform's [MediaExtractor] / [MediaMuxer], so no native
 * library or NDK build is involved and the APK stays small.
 *
 * Returns `true` on success; on failure the partial output file is deleted.
 */
object AudioExtractor {

    fun extract(src: File, dst: File): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            if (dst.exists()) dst.delete()

            extractor = MediaExtractor()
            extractor.setDataSource(src.absolutePath)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return false

            extractor.selectTrack(trackIndex)

            muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(format)
            muxer.start()

            val buf = ByteBuffer.allocate(1 shl 18)   // 256 KB
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outTrack, buf, info)
                extractor.advance()
            }

            muxer.stop()
            return dst.length() > 0
        } catch (e: Exception) {
            try {
                if (dst.exists()) dst.delete()
            } catch (_: Exception) {
            }
            return false
        } finally {
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            try {
                extractor?.release()
            } catch (_: Exception) {
            }
        }
    }
}
