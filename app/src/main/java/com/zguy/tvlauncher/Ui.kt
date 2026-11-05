package com.zguy.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.min

/**
 * Motion policy: every duration in the app goes through [d], so the "Animations" and
 * "Animation speed" settings apply everywhere consistently.
 */
class Motion(val level: Int, speedPct: Int) {
    val speed = speedPct.coerceIn(25, 300) / 100f
    val enabled get() = level != Prefs.MOTION_OFF

    /** Full motion: springs, slides, scale. Reduced keeps only short fades. */
    val full get() = level == Prefs.MOTION_FULL

    fun d(ms: Long): Long = when (level) {
        Prefs.MOTION_OFF -> 0L
        Prefs.MOTION_REDUCED -> (ms * 0.6f / speed).toLong()
        else -> (ms / speed).toLong()
    }

    /** Spring stiffness scaled with speed (a spring's duration scales with 1/sqrt(stiffness)). */
    val stiffness get() = 700f * speed * speed

    companion object {
        fun of(p: Prefs) = Motion(p.motionLevel, p.animSpeedPct)
        val EASE_OUT = PathInterpolator(0.2f, 0f, 0f, 1f)
        val EASE_IN = PathInterpolator(0.4f, 0f, 1f, 1f)
    }
}

/** Everything a card needs to draw itself, resolved to pixels once per style change. */
data class TileStyle(
    val columns: Int,
    /** Full cell width of the tile including the focus-ring gap. */
    val tileW: Int,
    val cardW: Int,
    val cardH: Int,
    val spacing: Int,
    val showLabels: Boolean,
    val accent: Int,
    val banners: Boolean,
    val cardColor: Int,
    val radius: Float,
    val strokeW: Int,
    val ringInset: Int,
    val focusScale: Float,
    val elevation: Float,
    /** Upward lift of the focused card, in px. */
    val lift: Float,
    val lightweight: Boolean,
    val motion: Motion,
)

object Ui {

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    /** Home carousel: one row, larger cards, stronger focus scale. [maxW] caps the card width in px. */
    fun rowStyle(ctx: Context, p: Prefs, maxW: Int = Int.MAX_VALUE, widthDp: Int = p.cardWidthDp): TileStyle {
        val dm = ctx.resources.displayMetrics
        val w = minOf(dp(ctx, widthDp), (dm.widthPixels * 0.42f).toInt(), maxW)
        return style(ctx, p, Int.MAX_VALUE, w, p.showLabels, 1.12f)
    }

    /**
     * Vertical padding the carousel needs so the focused (or lifted, moving) card, its ring and its
     * shadow are never clipped: half the extra height from scaling, plus the lift above and the shadow below.
     */
    fun rowPadding(ctx: Context, s: TileStyle): Pair<Int, Int> {
        val grow = ((s.cardH + 2 * s.ringInset) * (s.focusScale + 0.06f - 1f) / 2f).toInt()
        val top = grow + (s.lift * 2).toInt() + dp(ctx, 10)
        val bottom = grow + (s.elevation * 1.6f).toInt() + dp(ctx, 10)
        return top to bottom
    }

    /** Total carousel height for a style: padding + card + optional label. */
    fun rowHeight(ctx: Context, s: TileStyle, labelPx: Int): Int {
        val (t, b) = rowPadding(ctx, s)
        return t + s.cardH + 2 * s.ringInset + (if (s.showLabels) labelPx else 0) + b
    }

    /** All apps grid: fits [Prefs.columns] across the screen, always labelled. */
    fun gridStyle(ctx: Context, p: Prefs): TileStyle {
        val dm = ctx.resources.displayMetrics
        val spacing = dp(ctx, p.spacingDp)
        val maxW = (dm.widthPixels - dp(ctx, 96)) / p.columns - spacing
        val w = min(dp(ctx, p.cardWidthDp), maxW).coerceAtLeast(dp(ctx, 64))
        return style(ctx, p, p.columns, w, true, 1.08f)
    }

    private fun style(ctx: Context, p: Prefs, columns: Int, w: Int, labels: Boolean, scale: Float): TileStyle {
        val inset = dp(ctx, 5)
        val cardW = w - 2 * inset
        return TileStyle(
            columns = columns,
            tileW = w,
            cardW = cardW,
            cardH = cardW * 9 / 16,
            spacing = dp(ctx, p.spacingDp),
            showLabels = labels,
            accent = p.accentColor,
            banners = p.useBanners,
            cardColor = blend(p.overlayColor, Color.WHITE, 0.10f),
            radius = dpf(ctx, p.cornerDp.toFloat()),
            strokeW = dp(ctx, 3),
            ringInset = inset,
            focusScale = scale,
            elevation = if (p.lightweight) 0f else dpf(ctx, 14f),
            lift = dpf(ctx, 6f),
            lightweight = p.lightweight,
            motion = Motion.of(p),
        )
    }

    fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
    )

    fun withAlpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    fun ring(accent: Int, radius: Float, stroke: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radius
        setColor(Color.TRANSPARENT)
        setStroke(stroke, accent)
    }

    /** Focusable row / button background: subtle fill, accent outline when focused. */
    fun focusBackground(ctx: Context, accent: Int, radiusDp: Int, normalAlpha: Float = 0.06f): Drawable {
        val r = dpf(ctx, radiusDp.toFloat())
        val focused = GradientDrawable().apply {
            cornerRadius = r
            setColor(withAlpha(Color.WHITE, 0.14f))
            setStroke(dp(ctx, 2), accent)
        }
        val normal = GradientDrawable().apply {
            cornerRadius = r
            setColor(withAlpha(Color.WHITE, normalAlpha))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    /** Top navigation tab: bright pill when focused, soft glass pill for the current page. */
    fun tabBackground(ctx: Context, accent: Int, current: Boolean): Drawable {
        val r = dpf(ctx, 20f)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                cornerRadius = r
                setColor(withAlpha(blend(accent, Color.WHITE, 0.6f), 0.94f))
            })
            addState(intArrayOf(), GradientDrawable().apply {
                cornerRadius = r
                setColor(if (current) withAlpha(Color.WHITE, 0.16f) else Color.TRANSPARENT)
            })
        }
    }

    /** Simple focus scale for buttons and tabs (springs are reserved for cards). */
    fun focusScale(v: View, on: Boolean, motion: Motion, scale: Float = 1.05f) {
        val target = if (on && motion.enabled) scale else 1f
        v.animate().cancel()
        if (motion.full && v.isAttachedToWindow) {
            v.animate().scaleX(target).scaleY(target).setDuration(motion.d(160)).setInterpolator(Motion.EASE_OUT).start()
        } else {
            v.scaleX = target
            v.scaleY = target
        }
    }

    /** Bottom remote-control hints. Rebuilt only when the list changes. */
    fun setHints(container: LinearLayout, hints: List<Pair<String, String>>) {
        if (container.tag == hints) return
        container.tag = hints
        container.removeAllViews()
        container.visibility = if (hints.isEmpty()) View.INVISIBLE else View.VISIBLE
        val ctx = container.context
        for ((key, label) in hints) {
            val chip = TextView(ctx).apply {
                text = key
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                minWidth = dp(ctx, 26)
                minHeight = dp(ctx, 26)
                maxLines = 1
                setPadding(dp(ctx, 7), 0, dp(ctx, 7), 0)
                background = GradientDrawable().apply {
                    cornerRadius = dpf(ctx, 13f)
                    setStroke(dp(ctx, 2), withAlpha(Color.WHITE, 0.85f))
                }
            }
            val text = TextView(ctx).apply {
                text = label.uppercase()
                textSize = 14f
                letterSpacing = 0.08f
                setTextColor(withAlpha(Color.WHITE, 0.85f))
                tag = "text-medium"
                maxLines = 1
                setPadding(dp(ctx, 9), 0, dp(ctx, 30), 0)
            }
            chip.tag = "text-semibold"
            Fonts.style(ctx, chip)
            Fonts.style(ctx, text)
            container.addView(chip)
            container.addView(text)
        }
    }

    fun dialog(ctx: Context): AlertDialog.Builder =
        AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)

    /** Starts one of our own screens with a short fade (or instantly when motion is off). */
    fun start(activity: Activity, intent: Intent) {
        activity.startActivity(intent)
        @Suppress("DEPRECATION")
        if (activity.launcher.prefs.motionLevel == Prefs.MOTION_OFF) {
            activity.overridePendingTransition(0, 0)
        } else {
            activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    /**
     * Page change between Home and All Apps: a restrained fade with a slight move in the direction of travel
     * (forward = to the right). Reduced motion: fade only. Off: instant.
     */
    fun startPage(activity: Activity, intent: Intent, forward: Boolean) {
        activity.startActivity(intent)
        pageTransition(activity, forward)
    }

    @Suppress("DEPRECATION")
    fun pageTransition(activity: Activity, forward: Boolean) {
        when (activity.launcher.prefs.motionLevel) {
            Prefs.MOTION_OFF -> activity.overridePendingTransition(0, 0)
            Prefs.MOTION_REDUCED -> activity.overridePendingTransition(R.anim.page_fade_in, R.anim.page_fade_out)
            else -> if (forward) activity.overridePendingTransition(R.anim.page_in_from_right, R.anim.page_out_to_left)
            else activity.overridePendingTransition(R.anim.page_in_from_left, R.anim.page_out_to_right)
        }
    }

    /** Opens the settings panel; it animates itself, so the window transition is disabled. */
    fun openSettings(activity: Activity) {
        GlassView.captureSnapshot(activity)
        activity.startActivity(Intent(activity, SettingsActivity::class.java))
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }

    fun isDefaultHome(ctx: Context): Boolean {
        val ri = ctx.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        return ri?.activityInfo?.packageName == ctx.packageName
    }
}

/** Focus a position once it has actually been laid out (shared by row and grid managers). */
private class PendingFocus {
    var position = -1

    fun request(lm: RecyclerView.LayoutManager, rv: RecyclerView, position: Int, scrollTo: (Int) -> Unit): Boolean {
        val n = rv.adapter?.itemCount ?: 0
        if (n == 0) return false
        val p = position.coerceIn(0, n - 1)
        if (!rv.isLayoutRequested && !rv.hasPendingAdapterUpdates()) {
            val v = lm.findViewByPosition(p)
            if (v != null) {
                this.position = -1
                v.requestFocus()
                return true
            }
        }
        this.position = p
        scrollTo(p)
        return true
    }

    fun onLayoutCompleted(lm: RecyclerView.LayoutManager) {
        val p = position
        if (p < 0) return
        val v = lm.findViewByPosition(p) ?: return
        position = -1
        v.post { v.requestFocus() }
    }
}

/**
 * Home carousel layout manager. Keeps the focused card inside a comfortable zone
 * (about one card from the left edge, 1.5 cards from the right) and scrolls there smoothly.
 * A new key press retargets the running scroll instead of queueing another one.
 */
class FocusRowLayoutManager(ctx: Context) : LinearLayoutManager(ctx, HORIZONTAL, false) {

    var motion: Motion = Motion(Prefs.MOTION_FULL, 100)
    private val pending = PendingFocus()
    private val decel = DecelerateInterpolator(1.8f)

    fun focusPosition(rv: RecyclerView, position: Int) = pending.request(this, rv, position) { scrollToPosition(it) }

    override fun onLayoutCompleted(state: RecyclerView.State?) {
        super.onLayoutCompleted(state)
        pending.onLayoutCompleted(this)
    }

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean,
    ): Boolean {
        val left = getDecoratedLeft(child)
        val right = getDecoratedRight(child)
        val cw = right - left
        val minLeft = paddingLeft + cw
        val maxRight = width - paddingRight - (cw * 1.5f).toInt()
        val dx = when {
            left < minLeft -> left - minLeft
            right > maxRight -> right - maxRight
            else -> 0
        }
        if (dx == 0) return false
        val d = motion.d(260).toInt()
        if (immediate || d <= 0 || !motion.full) parent.scrollBy(dx, 0) else parent.smoothScrollBy(dx, 0, decel, d)
        return true
    }
}

/** All apps grid layout manager with reliable focus restore. */
class FocusGridLayoutManager(ctx: Context, spanCount: Int) : GridLayoutManager(ctx, spanCount) {

    private val pending = PendingFocus()

    fun focusPosition(rv: RecyclerView, position: Int) = pending.request(this, rv, position) { scrollToPosition(it) }

    override fun onLayoutCompleted(state: RecyclerView.State?) {
        super.onLayoutCompleted(state)
        pending.onLayoutCompleted(this)
    }
}
