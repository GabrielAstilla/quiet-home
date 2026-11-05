package com.zguy.tvlauncher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import kotlin.math.max

/**
 * Liquid-glass approximation.
 *
 * Real-time blur of whatever is behind a view is expensive on TV GPUs, so glass surfaces instead
 * draw a small, pre-blurred picture of the background ([GlassHub]) aligned to their screen position,
 * then a tint, a soft top reflection and a fine light edge. Panels and menus use a blurred snapshot
 * of the screen taken when they open, so the content behind them shows through.
 * With blur off or in Lightweight mode, the surface is a plain translucent tint over the real screen.
 */
class GlassHub {
    var current: Bitmap? = null
        private set
    private val views = ArrayList<GlassView>()

    fun attach(v: GlassView) {
        views += v
    }

    fun detach(v: GlassView) {
        views -= v
    }

    /** New background published (already blurred, screen aspect). Glass crossfades to it. */
    fun publish(bmp: Bitmap?, durationMs: Long) {
        current = bmp
        views.toList().forEach { it.onSource(bmp, durationMs) }
    }

    fun restyle() = views.toList().forEach { it.refreshStyle() }
}

class GlassView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {

    var cornerRadius = Ui.dpf(ctx, 22f)
        set(value) {
            field = value
            invalidateOutline()
            invalidate()
        }

    /** When set, draws this snapshot instead of the live background (used by panels and menus). */
    var snapshot: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Extra tint opacity for surfaces that hold a lot of text (menus, panels). */
    var opacityBoost = 0f

    /** Fully rounded ends (radius = half the height). */
    var capsule = false

    /** Draw nothing (no tint, edge or shadow): content sits directly on the wallpaper. */
    var plain = false
        set(value) {
            field = value
            refreshStyle()
        }

    private val app = ctx.launcher
    private var source: Bitmap? = null
    private var previous: Bitmap? = null
    private var mix = 1f
    private var mixAnim: ValueAnimator? = null

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint()
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val loc = IntArray(2)

    private var blurOn = true
    private var lightweight = false
    private var tint = Color.BLACK
    private var opacity = 0.3f

    init {
        setWillNotDraw(false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        clipToOutline = true
        edgePaint.strokeWidth = max(1f, Ui.dpf(ctx, 1f))
        refreshStyle()
    }

    fun refreshStyle() {
        val p = app.prefs
        lightweight = p.lightweight
        blurOn = !lightweight && p.glassBlur > 0
        tint = p.glassTint
        // Without real blur, a stronger tint keeps text readable over busy wallpapers.
        val base = p.glassOpacity / 100f + opacityBoost
        // Translucent fallback (no blur): sharp content behind would compete with text, so tint harder —
        // much harder on text-heavy surfaces (panels, menus) than on small capsules.
        opacity = if (blurOn) base.coerceIn(0f, 0.95f)
        else max(base + (if (opacityBoost > 0f) 0.25f else 0.1f), if (opacityBoost > 0f) 0.93f else 0.55f).coerceAtMost(0.97f)
        elevation = if (lightweight || plain) 0f else Ui.dpf(context, 12f)
        clipToOutline = !plain
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        app.glassHub.attach(this)
        source = app.glassHub.current
        refreshStyle()
    }

    override fun onDetachedFromWindow() {
        app.glassHub.detach(this)
        mixAnim?.cancel()
        super.onDetachedFromWindow()
    }

    fun onSource(bmp: Bitmap?, durationMs: Long) {
        mixAnim?.cancel()
        if (durationMs <= 0 || source == null) {
            previous = null
            source = bmp
            mix = 1f
            invalidate()
            return
        }
        previous = source
        source = bmp
        mix = 0f
        mixAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener {
                mix = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (capsule) cornerRadius = h / 2f
        highlightPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.55f,
            Ui.withAlpha(Color.WHITE, 0.10f), Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
        edgePaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            Ui.withAlpha(Color.WHITE, 0.42f), Ui.withAlpha(Color.WHITE, 0.07f), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (plain) return super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (blurOn) {
            val snap = snapshot
            if (snap != null) drawSampled(canvas, snap, 255)
            else {
                val prev = previous
                if (prev != null && mix < 1f) drawSampled(canvas, prev, 255)
                source?.let { drawSampled(canvas, it, (mix * 255).toInt()) }
            }
        }
        tintPaint.color = Ui.withAlpha(tint, opacity)
        canvas.drawRect(0f, 0f, w, h, tintPaint)
        if (!lightweight) canvas.drawRect(0f, 0f, w, h * 0.55f, highlightPaint)
        val inset = edgePaint.strokeWidth / 2f
        rect.set(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, edgePaint)
        super.onDraw(canvas)
    }

    /** Draws [bmp] (a full-screen picture) so it lines up with what is behind this view. */
    private fun drawSampled(canvas: Canvas, bmp: Bitmap, alpha: Int) {
        val root = rootView ?: return
        if (root.width == 0 || root.height == 0) return
        getLocationInWindow(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        canvas.scale(root.width / bmp.width.toFloat(), root.height / bmp.height.toFloat())
        bmpPaint.alpha = alpha
        canvas.drawBitmap(bmp, 0f, 0f, bmpPaint)
        canvas.restore()
    }

    companion object {
        /**
         * Captures a small blurred snapshot of the current screen for panels/menus about to open.
         * Translucent screens (like Settings) are layered over the previous snapshot.
         */
        fun captureSnapshot(activity: Activity, translucent: Boolean = false) {
            val app = activity.launcher
            if (app.prefs.lightweight || app.prefs.glassBlur == 0) {
                app.snapshot = null
                return
            }
            val root = activity.window.decorView
            if (root.width == 0 || root.height == 0) return
            val w = 240
            val h = (w * root.height / root.width.toFloat()).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val under = app.snapshot
            if (translucent && under != null) c.drawBitmap(under, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            c.scale(w / root.width.toFloat(), h / root.height.toFloat())
            try {
                root.draw(c)
            } catch (e: Exception) {
                // Some views refuse software drawing; the panel then shows the live background only.
            }
            app.snapshot = ImageOps.boxBlur(bmp, blurRadius(app.prefs.glassBlur))
        }

        fun blurRadius(level: Int) = when (level) {
            1 -> 2
            2 -> 3
            else -> 5
        }
    }
}

/**
 * The focused card's luminous border: a white-to-accent gradient ring, a faint outer glow and a
 * restrained reflection across the top of the artwork. One alpha drives all three, so border,
 * glow and highlight always animate together.
 */
class GlassRingDrawable(
    private val accent: Int,
    private val ringRadius: Float,
    private val cardInset: Float,
    private val cardRadius: Float,
    stroke: Float,
    private val glow: Boolean,
) : Drawable() {

    var strokeWidth = stroke
        set(value) {
            field = value
            invalidateSelf()
        }

    private var a = 255
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onBoundsChange(b: android.graphics.Rect) {
        ring.shader = LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            Color.WHITE, Ui.blend(accent, Color.WHITE, 0.25f), Shader.TileMode.CLAMP,
        )
        val ch = b.height() - 2 * cardInset
        shine.shader = LinearGradient(
            0f, b.top + cardInset, 0f, b.top + cardInset + ch * 0.45f,
            Ui.withAlpha(Color.WHITE, 0.16f), Color.TRANSPARENT, Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        if (a == 0) return
        val b = bounds
        if (glow) {
            glowPaint.color = Ui.withAlpha(Ui.blend(accent, Color.WHITE, 0.4f), 0.14f * a / 255f)
            glowPaint.strokeWidth = strokeWidth * 3f
            r.set(b.left - strokeWidth * 0.5f, b.top - strokeWidth * 0.5f, b.right + strokeWidth * 0.5f, b.bottom + strokeWidth * 0.5f)
            canvas.drawRoundRect(r, ringRadius + strokeWidth, ringRadius + strokeWidth, glowPaint)
            shine.alpha = a
            r.set(b.left + cardInset, b.top + cardInset, b.right - cardInset, b.bottom - cardInset)
            canvas.drawRoundRect(r, cardRadius, cardRadius, shine)
        }
        ring.strokeWidth = strokeWidth
        ring.alpha = a
        val half = strokeWidth / 2f
        r.set(b.left + half, b.top + half, b.right - half, b.bottom - half)
        canvas.drawRoundRect(r, ringRadius - half, ringRadius - half, ring)
    }

    override fun setAlpha(alpha: Int) {
        if (a != alpha) {
            a = alpha
            invalidateSelf()
        }
    }

    override fun getAlpha() = a
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
