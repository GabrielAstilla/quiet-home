package com.zguy.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

/** Where user images live. Everything is private app storage; nothing leaves the TV. */
object ImageFiles {

    fun wallpaper(ctx: Context) = File(ctx.filesDir, "wallpaper.jpg")

    fun appBackground(ctx: Context, key: String) = File(dir(ctx, "bg"), sha(key) + ".jpg")

    fun appArtwork(ctx: Context, key: String) = File(dir(ctx, "art"), sha(key) + ".jpg")

    fun temp(ctx: Context) = File(ctx.cacheDir, "import_${System.nanoTime()}.jpg")

    fun clearDir(ctx: Context, name: String) {
        dir(ctx, name).listFiles()?.forEach { it.delete() }
    }

    private fun dir(ctx: Context, name: String) = File(ctx.filesDir, name).apply { mkdirs() }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Decoding, resizing, cropping and blurring. All of it runs on background threads. */
object ImageOps {

    private fun bounds(open: () -> InputStream?): BitmapFactory.Options? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Bounds-only decoding always returns null, so check the stream opened instead.
        open()?.use { BitmapFactory.decodeStream(it, null, o); true } ?: return null
        return if (o.outWidth > 0 && o.outHeight > 0) o else null
    }

    private fun sampleFor(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var s = 1
        while (w / (s * 2) >= reqW && h / (s * 2) >= reqH) s *= 2
        return s
    }

    /** Imports an image keeping its aspect ratio, downscaled to fit within maxW x maxH. */
    fun importScaled(open: () -> InputStream?, out: File, maxW: Int, maxH: Int): Boolean {
        val b = bounds(open) ?: return false
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(b.outWidth, b.outHeight, maxW, maxH) }
        var bmp = open()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false
        val scale = min(1f, min(maxW / bmp.width.toFloat(), maxH / bmp.height.toFloat()))
        if (scale < 1f) {
            val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        return write(bmp, out)
    }

    /** Imports an image centre-cropped to exactly w x h (used for custom card artwork). */
    fun importCropped(open: () -> InputStream?, out: File, w: Int, h: Int): Boolean {
        val b = bounds(open) ?: return false
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleFor(b.outWidth, b.outHeight, w, h) }
        val src = open()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false
        val dst = cover(src, w, h)
        src.recycle()
        return write(dst, out)
    }

    fun cover(src: Bitmap, w: Int, h: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val scale = max(w / src.width.toFloat(), h / src.height.toFloat())
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate((w - src.width * scale) / 2f, (h - src.height * scale) / 2f)
        }
        val dst = Bitmap.createBitmap(w, h, config)
        Canvas(dst).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return dst
    }

    private fun write(bmp: Bitmap, out: File): Boolean {
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".tmp")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        return tmp.renameTo(out)
    }

    /**
     * Decodes a background image. Blur is done once, here, on a small copy:
     * the image is shrunk (more for stronger blur), box-blurred, and the ImageView scales it up.
     * That keeps blur nearly free at draw time, even on slow TV GPUs.
     */
    fun decodeBackground(file: File, blurLevel: Int, screenW: Int): Bitmap? {
        if (!file.exists()) return null
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, b)
        if (b.outWidth <= 0) return null
        if (blurLevel <= 0) {
            val o = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = sampleFor(b.outWidth, b.outHeight, screenW, 1)
            }
            return BitmapFactory.decodeFile(file.path, o)
        }
        val targetW = when (blurLevel) {
            1 -> 480
            2 -> 240
            else -> 128
        }
        val o = BitmapFactory.Options().apply { inSampleSize = sampleFor(b.outWidth, b.outHeight, targetW, 1) }
        val src = BitmapFactory.decodeFile(file.path, o) ?: return null
        val h = (targetW * src.height / src.width.toFloat()).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, targetW, h, true)
        if (small !== src) src.recycle()
        val mutable = if (small.isMutable) small else small.copy(Bitmap.Config.ARGB_8888, true).also { small.recycle() }
        return boxBlur(mutable, if (blurLevel == 1) 2 else 3)
    }

    /** Three-pass box blur (approximates a Gaussian). In place; intended for small bitmaps. */
    fun boxBlur(bmp: Bitmap, radius: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val a = IntArray(w * h)
        val t = IntArray(w * h)
        bmp.getPixels(a, 0, w, 0, 0, w, h)
        repeat(3) {
            pass(a, t, w, h, radius, horizontal = true)
            pass(t, a, w, h, radius, horizontal = false)
        }
        bmp.setPixels(a, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun idx(i: Int): Int {
                val c = i.coerceIn(0, len - 1)
                return if (horizontal) line * w + c else c * w + line
            }
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            for (k in -r..r) {
                val p = src[idx(k)]
                sa += p ushr 24; sr += p shr 16 and 0xff; sg += p shr 8 and 0xff; sb += p and 0xff
            }
            for (i in 0 until len) {
                dst[idx(i)] = (sa / div shl 24) or (sr / div shl 16) or (sg / div shl 8) or (sb / div)
                val pOut = src[idx(i - r)]
                val pIn = src[idx(i + r + 1)]
                sa += (pIn ushr 24) - (pOut ushr 24)
                sr += (pIn shr 16 and 0xff) - (pOut shr 16 and 0xff)
                sg += (pIn shr 8 and 0xff) - (pOut shr 8 and 0xff)
                sb += (pIn and 0xff) - (pOut and 0xff)
            }
        }
    }

    fun isImage(f: File): Boolean {
        val n = f.name.lowercase()
        return f.isFile && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))
    }
}
