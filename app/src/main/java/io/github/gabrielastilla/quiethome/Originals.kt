package io.github.gabrielastilla.quiethome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Quiet Home Originals: an Apple-inspired wallpaper collection (aerial landscapes, oceans, nature,
 * abstract). They are NOT Apple images; each one is drawn procedurally by this app, so there is
 * nothing to license and nothing large in the APK. Rendered at half size, then smoothly upscaled.
 */
object Originals {

    class Item(val id: String, val name: String, val category: String, val draw: (Canvas, Int, Int, Random) -> Unit)

    val ALL: List<Item> = listOf(
        Item("coast", "Coastline", "Aerial · Ocean", ::coast),
        Item("lagoon", "Lagoon", "Aerial · Ocean", ::lagoon),
        Item("deep", "Deep Sea", "Ocean", ::deepSea),
        Item("dunes", "Dunes", "Aerial · Nature", ::dunes),
        Item("canyon", "Canyon", "Aerial · Nature", ::canyon),
        Item("alpine", "Alpine Dusk", "Nature", ::alpine),
        Item("forest", "Forest Mist", "Nature", ::forest),
        Item("aurora", "Aurora", "Nature · Abstract", ::aurora),
        Item("bloom", "Bloom", "Abstract", ::bloom),
        Item("silk", "Silk", "Abstract", ::silk),
    )

    fun find(id: String) = ALL.firstOrNull { it.id == id }

    /** Renders [item] at [w]×[h] (rendered at half size and smoothly upscaled). */
    fun render(item: Item, w: Int, h: Int): Bitmap {
        val rw = (w / 2).coerceAtLeast(64)
        val rh = (h / 2).coerceAtLeast(36)
        val small = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
        item.draw(Canvas(small), rw, rh, Random(item.id.hashCode()))
        ImageOps.boxBlur(small, 1)
        val out = Bitmap.createScaledBitmap(small, w, h, true)
        if (out !== small) small.recycle()
        return out
    }

    private fun lin(x0: Float, y0: Float, x1: Float, y1: Float, vararg c: Int) =
        LinearGradient(x0, y0, x1, y1, c, null, Shader.TileMode.CLAMP)

    private fun fill(c: Canvas, w: Int, h: Int, shader: Shader) =
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { this.shader = shader })

    private fun blob(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(x, y, r, Ui.withAlpha(color, alpha), Ui.withAlpha(color, 0f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
    }

    /** A wavy band from x=0..w following y = base + Σ sin. */
    private fun wave(w: Int, base: Float, amp: Float, freq: Float, phase: Float, bottom: Float): Path {
        val p = Path()
        p.moveTo(0f, bottom)
        var x = 0f
        while (x <= w) {
            val y = base + amp * sin(x * freq + phase) + amp * 0.35f * sin(x * freq * 2.7f + phase * 1.3f)
            p.lineTo(x, y)
            x += 4f
        }
        p.lineTo(w.toFloat(), bottom)
        p.close()
        return p
    }

    /** Midpoint-displacement ridge line, closed down to the bottom edge. */
    private fun ridge(w: Int, h: Int, base: Float, rough: Float, rnd: Random): Path {
        val n = 65
        val ys = FloatArray(n)
        ys[0] = base + (rnd.nextFloat() - 0.5f) * rough
        ys[n - 1] = base + (rnd.nextFloat() - 0.5f) * rough
        var step = n - 1
        var r = rough
        while (step > 1) {
            var i = step / 2
            while (i < n) {
                ys[i] = (ys[i - step / 2] + ys[(i + step / 2).coerceAtMost(n - 1)]) / 2 + (rnd.nextFloat() - 0.5f) * r
                i += step
            }
            step /= 2
            r *= 0.55f
        }
        val p = Path()
        p.moveTo(0f, h.toFloat())
        for (i in 0 until n) p.lineTo(w * i / (n - 1f), ys[i])
        p.lineTo(w.toFloat(), h.toFloat())
        p.close()
        return p
    }

    private fun coast(c: Canvas, w: Int, h: Int, r: Random) {
        fill(c, w, h, lin(0f, 0f, w.toFloat(), h.toFloat(), 0xFF06304F.toInt(), 0xFF0B5E7E.toInt(), 0xFF1597A8.toInt()))
        val sand = Path().apply {
            moveTo(w * 0.55f, h.toFloat())
            var y = h.toFloat()
            while (y >= 0) {
                val x = w * (0.52f + 0.38f * (1 - y / h)) + 18f * sin(y * 0.035f) + 8f * sin(y * 0.11f)
                lineTo(x, y)
                y -= 3f
            }
            lineTo(w.toFloat(), 0f)
            lineTo(w.toFloat(), h.toFloat())
            close()
        }
        // Shallow water glow along the shore, then sand, then foam lines.
        c.save()
        c.translate(-w * 0.05f, 0f)
        c.drawPath(sand, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.withAlpha(0xFF5FE0D8.toInt(), 0.45f) })
        c.restore()
        c.drawPath(sand, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = lin(w * 0.5f, 0f, w.toFloat(), h.toFloat(), 0xFFEAD9B6.toInt(), 0xFFC9AE82.toInt())
        })
        val foam = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f }
        for (k in 0..2) {
            c.save()
            c.translate(-w * (0.012f + k * 0.022f), 0f)
            foam.color = Ui.withAlpha(Color.WHITE, 0.55f - k * 0.16f)
            c.drawPath(sand, foam)
            c.restore()
        }
    }

    private fun lagoon(c: Canvas, w: Int, h: Int, r: Random) {
        fill(c, w, h, lin(0f, 0f, 0f, h.toFloat(), 0xFF052A4A.toInt(), 0xFF07446B.toInt()))
        repeat(7) {
            blob(c, r.nextFloat() * w, r.nextFloat() * h, (0.18f + r.nextFloat() * 0.25f) * w, 0xFF2BC4C9.toInt(), 0.55f)
        }
        repeat(5) {
            blob(c, r.nextFloat() * w, r.nextFloat() * h, (0.04f + r.nextFloat() * 0.06f) * w, 0xFFF4E6C4.toInt(), 0.65f)
        }
    }

    private fun deepSea(c: Canvas, w: Int, h: Int, r: Random) {
        fill(c, w, h, lin(0f, 0f, 0f, h.toFloat(), 0xFF0A4D7A.toInt(), 0xFF03182E.toInt(), 0xFF01060F.toInt()))
        val ray = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(6) {
            val x = r.nextFloat() * w
            val spread = w * (0.05f + r.nextFloat() * 0.08f)
            ray.shader = lin(0f, 0f, 0f, h * 0.9f, Ui.withAlpha(0xFF9FE6FF.toInt(), 0.18f), Color.TRANSPARENT)
            c.drawPath(Path().apply {
                moveTo(x - spread * 0.3f, 0f); lineTo(x + spread * 0.3f, 0f)
                lineTo(x + spread * 1.6f, h.toFloat()); lineTo(x - spread * 0.6f, h.toFloat()); close()
            }, ray)
        }
    }

    private fun dunes(c: Canvas, w: Int, h: Int, r: Random) {
        fill(c, w, h, lin(0f, 0f, 0f, h.toFloat(), 0xFFE9A86A.toInt(), 0xFFC9733F.toInt()))
        val bands = 9
        for (i in 0 until bands) {
            val base = h * (i + 0.6f) / bands
            val path = wave(w, base, h * 0.035f, 0.006f + r.nextFloat() * 0.004f, r.nextFloat() * 6f, h.toFloat())
            val light = Ui.blend(0xFFF6C38E.toInt(), 0xFFD9824A.toInt(), i / bands.toFloat())
            val dark = Ui.blend(0xFFB8603A.toInt(), 0xFF8A3E22.toInt(), i / bands.toFloat())
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = lin(0f, base - h * 0.04f, 0f, base + h * 0.12f, light, dark) })
        }
    }

    private fun canyon(c: Canvas, w: Int, h: Int, r: Random) {
        fill(c, w, h, lin(0f, 0f, 0f, h.toFloat(), 0xFF3A1A2E.toInt(), 0xFF8C3B2A.toInt()))
        val colors = intArrayOf(0xFFB5523A.toInt(), 0xFF9C4130.toInt(), 0xFF7E3326.toInt(), 0xFF5E241C.toInt(), 0xFF3D1712.toInt())
        colors.forEachIndexed { i, col ->
            c.drawPath(ridge(w, h, h * (0.35f + i * 0.13f), h * 0.22f, r), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = lin(0f, h * (0.3f + i * 0.13f), 0f, h.toFloat(), col, Ui.blend(col, Color.BLACK, 0.45f))
            })
        }
    }

    private fun alpine(c: Canvas, w: Int, h: Int, r: Random) {
        fill(c, w, h, lin(0f, 0f, 0f, h.toFloat(), 0xFF141E45.toInt(), 0xFF4A3F7A.toInt(), 0xFFE58F6C.toInt()))
        blob(c, w * 0.7f, h * 0.62f, w * 0.22f, 0xFFFFD2A0.toInt(), 0.55f)
        val ridges = intArrayOf(0xFF8A7FB0.toInt(), 0xFF5D5690.toInt(), 0xFF3A366A.toInt(), 0xFF1D1B3D.toInt())
        ridges.forEachIndexed { i, col ->
            c.drawPath(ridge(w, h, h * (0.48f + i * 0.11f), h * (0.35f - i * 0.05f), r), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
            fill(c, w, h, lin(0f, h * (0.5f + i * 0.11f), 0f, h.toFloat(), Color.TRANSPARENT, Ui.withAlpha(0xFFE7B3A0.toInt(), 0.10f)))
        }
    }

    private fun forest(c: Canvas, w: Int, h: Int, r: Random) {
        fill(c, w, h, lin(0f, 0f, 0f, h.toFloat(), 0xFFAFC4C0.toInt(), 0xFF5F7F76.toInt()))
        val greens = intArrayOf(0xFF6E8C80.toInt(), 0xFF4D6B5E.toInt(), 0xFF314D41.toInt(), 0xFF1A2E26.toInt(), 0xFF0D1A15.toInt())
        greens.forEachIndexed { i, col ->
            c.drawPath(ridge(w, h, h * (0.38f + i * 0.12f), h * 0.18f, r), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
            fill(c, w, h, lin(0f, h * (0.35f + i * 0.12f), 0f, h * (0.6f + i * 0.12f), Ui.withAlpha(Color.WHITE, 0.18f), Color.TRANSPARENT))
        }
    }

    private fun aurora(c: Canvas, w: Int, h: Int, r: Random) {
        fill(c, w, h, lin(0f, 0f, 0f, h.toFloat(), 0xFF020617.toInt(), 0xFF061A2E.toInt(), 0xFF0B2B3A.toInt()))
        val star = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(120) {
            star.color = Ui.withAlpha(Color.WHITE, 0.3f + r.nextFloat() * 0.6f)
            c.drawCircle(r.nextFloat() * w, r.nextFloat() * h * 0.7f, 0.5f + r.nextFloat() * 0.8f, star)
        }
        val tmp = Bitmap.createBitmap(w / 2, h / 2, Bitmap.Config.ARGB_8888)
        val tc = Canvas(tmp)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        listOf(0xFF3CF0A0.toInt(), 0xFF2BD3C9.toInt(), 0xFF8A5CF6.toInt()).forEachIndexed { i, col ->
            stroke.color = Ui.withAlpha(col, 0.7f)
            stroke.strokeWidth = tmp.height * (0.10f - i * 0.02f)
            tc.drawPath(Path().apply {
                moveTo(-10f, tmp.height * (0.55f + i * 0.08f))
                cubicTo(tmp.width * 0.3f, tmp.height * (0.15f + i * 0.1f), tmp.width * 0.6f, tmp.height * (0.7f - i * 0.1f), tmp.width + 10f, tmp.height * (0.25f + i * 0.08f))
            }, stroke)
        }
        ImageOps.boxBlur(tmp, 6)
        c.drawBitmap(tmp, null, android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        tmp.recycle()
        c.drawPath(ridge(w, h, h * 0.86f, h * 0.08f, r), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF02070D.toInt() })
    }

    private fun bloom(c: Canvas, w: Int, h: Int, r: Random) {
        fill(c, w, h, lin(0f, 0f, w.toFloat(), h.toFloat(), 0xFF140A2E.toInt(), 0xFF0A0F2E.toInt()))
        val tmp = Bitmap.createBitmap(w / 3, h / 3, Bitmap.Config.ARGB_8888)
        val tc = Canvas(tmp)
        val cols = intArrayOf(0xFFFF4FA3.toInt(), 0xFFFF8A3D.toInt(), 0xFF6A5CFF.toInt(), 0xFF2FB8FF.toInt(), 0xFFB04FFF.toInt())
        cols.forEachIndexed { i, col ->
            blob(tc, tmp.width * (0.15f + 0.7f * r.nextFloat()), tmp.height * (0.2f + 0.6f * r.nextFloat()), tmp.width * (0.28f + 0.15f * r.nextFloat()), col, 0.9f)
        }
        ImageOps.boxBlur(tmp, 5)
        c.drawBitmap(tmp, null, android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        tmp.recycle()
    }

    private fun silk(c: Canvas, w: Int, h: Int, r: Random) {
        // A smooth colour field from layered sine waves, computed at low resolution.
        val sw = 192
        val sh = (sw * h / w.toFloat()).toInt().coerceAtLeast(1)
        val px = IntArray(sw * sh)
        val a = intArrayOf(0x1B, 0x2A, 0x6B)
        val b = intArrayOf(0x5E, 0x8B, 0xE8)
        val d = intArrayOf(0xC9, 0x7B, 0xE0)
        val p1 = r.nextFloat() * 6f
        val p2 = r.nextFloat() * 6f
        for (y in 0 until sh) for (x in 0 until sw) {
            val u = x / sw.toFloat()
            val v = y / sh.toFloat()
            val t = 0.5f + 0.5f * sin((u * 3.1f + v * 1.7f) * PI.toFloat() + p1 + sin(v * 4f + p2) * 0.9f)
            val s2 = 0.5f + 0.5f * sin((u * 1.3f - v * 2.4f) * PI.toFloat() + p2)
            val ch = IntArray(3) { k -> ((a[k] * (1 - t) + b[k] * t) * (1 - s2 * 0.45f) + d[k] * s2 * 0.45f).toInt().coerceIn(0, 255) }
            px[y * sw + x] = Color.rgb(ch[0], ch[1], ch[2])
        }
        val small = Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888)
        c.drawBitmap(small, null, android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        small.recycle()
    }
}
