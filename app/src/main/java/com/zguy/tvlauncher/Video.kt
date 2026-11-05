package com.zguy.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File

/** Checks whether this TV can decode a video, and extracts still frames. Runs on background threads. */
object Video {

    class Info(val file: File, val width: Int, val height: Int, val durationMs: Long, val mime: String?, val supported: Boolean, val reason: String?)

    private val EXTENSIONS = setOf("mp4", "m4v", "mov", "mkv", "webm", "ts")

    fun isVideo(f: File) = f.isFile && f.extension.lowercase() in EXTENSIONS

    fun probe(file: File): Info {
        if (!file.exists()) return Info(file, 0, 0, 0, null, false, "File missing")
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                val w = fmt.getIntegerSafe(MediaFormat.KEY_WIDTH)
                val h = fmt.getIntegerSafe(MediaFormat.KEY_HEIGHT)
                val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
                // Ask the TV's codecs directly: e.g. many Apple aerials are 4K HEVC / Dolby Vision.
                val decoder = runCatching {
                    MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(fmt)
                }.getOrNull()
                return if (decoder != null) Info(file, w, h, dur, mime, true, null)
                else Info(file, w, h, dur, mime, false, "This TV can't decode ${mime.removePrefix("video/").uppercase()} at ${w}×$h")
            }
            return Info(file, 0, 0, 0, null, false, "No video track")
        } catch (e: Exception) {
            return Info(file, 0, 0, 0, null, false, "Unreadable file")
        } finally {
            ex.release()
        }
    }

    private fun MediaFormat.getIntegerSafe(k: String) = if (containsKey(k)) getInteger(k) else 0

    /** A frame from [atFraction] of the way through, scaled to at most [maxW] wide. */
    fun frame(file: File, maxW: Int, atFraction: Float = 0.33f): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val t = (dur * atFraction * 1000).toLong()
            val src = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            if (src.width <= maxW) src
            else Bitmap.createScaledBitmap(src, maxW, (maxW * src.height / src.width.toFloat()).toInt(), true).also { if (it !== src) src.recycle() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Cached still frame (first frame shown before playback starts, and the still alternative). */
    fun stillFile(ctx: Context, video: File) = File(File(ctx.cacheDir, "stills").apply { mkdirs() }, "${video.name.hashCode()}_${video.lastModified()}.jpg")

    fun ensureStill(ctx: Context, video: File): File? {
        val out = stillFile(ctx, video)
        if (out.exists()) return out
        val bmp = frame(video, 1920) ?: return null
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        return out
    }
}
