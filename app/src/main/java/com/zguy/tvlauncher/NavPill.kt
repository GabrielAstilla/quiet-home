package com.zguy.tvlauncher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * The single glass pill behind the Home · All Apps · Settings labels.
 *
 * Its left and right edges are two independent springs. The edge moving in the direction of travel
 * is stiffer than the trailing edge, so the pill stretches slightly as it slides and then settles —
 * with damping ≈0.8 there is almost no overshoot. A third spring blends between the "resting on the
 * current page" look (subtle) and the "remote focus" look (bright, tinted, lifted). Every spring
 * retargets from its current value, so rapid presses never snap or queue.
 *
 * The current page always keeps its own faint marker, so focus and active page stay distinguishable.
 */
class NavPill @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    /** Called on every frame so the tab labels can recolour under the pill. */
    var onFrame: (() -> Unit)? = null

    private val leftHolder = FloatValueHolder(0f)
    private val rightHolder = FloatValueHolder(0f)
    private val focusHolder = FloatValueHolder(0f)
    private val leftSpring = SpringAnimation(leftHolder)
    private val rightSpring = SpringAnimation(rightHolder)
    private val focusSpring = SpringAnimation(focusHolder)
    private var colorAnim: ValueAnimator? = null

    val left get() = leftHolder.value
    val right get() = rightHolder.value
    /** 0 = resting on the active page, 1 = showing remote focus. */
    val focusMix get() = focusHolder.value

    private var activeL = 0f
    private var activeR = 0f
    private var placed = false

    private var accent = Color.WHITE
    private var lightweight = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        // Shadows on shapes render with hardware acceleration from API 28; earlier, no shadow.
        if (Build.VERSION.SDK_INT < 28) setLayerType(LAYER_TYPE_SOFTWARE, null)
        edge.strokeWidth = Ui.dpf(ctx, 1f)
        for (s in listOf(leftSpring, rightSpring, focusSpring)) {
            s.addUpdateListener { _, _, _ -> frame() }
        }
        focusSpring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
        leftSpring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS)
        rightSpring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS)
    }

    /** Never drives the glass capsule's size: it takes exactly the size the capsule gives it (the labels' size). */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun size(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(size(widthMeasureSpec), size(heightMeasureSpec))
    }

    fun setStyle(accent: Int, lightweight: Boolean) {
        this.accent = accent
        this.lightweight = lightweight
        frame()
    }

    /** Where the current page's tab is (its faint marker). */
    fun setActive(l: Float, r: Float) {
        activeL = l
        activeR = r
        frame()
    }

    /**
     * Moves the pill to [l]..[r]. [focused] true = bright focus look, false = resting on the active page.
     */
    fun moveTo(l: Float, r: Float, focused: Boolean, motion: Motion) {
        val target = if (focused) 1f else 0f
        if (!placed || !motion.enabled) {
            placed = true
            for (s in listOf(leftSpring, rightSpring, focusSpring)) s.cancel()
            colorAnim?.cancel()
            leftHolder.value = l
            rightHolder.value = r
            focusHolder.value = target
            frame()
            return
        }
        if (!motion.full) {
            // Reduced motion: no sliding or stretching; the pill moves at once and only its look crossfades.
            leftSpring.cancel()
            rightSpring.cancel()
            leftHolder.value = l
            rightHolder.value = r
            fadeFocus(target, motion.d(160))
            return
        }
        val movingRight = (l + r) / 2f > (left + right) / 2f
        val k = motion.stiffness
        // Leading edge stiffer than trailing: a brief stretch, then settle. Damping 0.8 ≈ minimal overshoot.
        leftSpring.spring = SpringForce(l).setDampingRatio(0.82f).setStiffness(if (movingRight) k * 0.55f else k * 1.15f)
        rightSpring.spring = SpringForce(r).setDampingRatio(0.82f).setStiffness(if (movingRight) k * 1.15f else k * 0.55f)
        leftSpring.animateToFinalPosition(l)
        rightSpring.animateToFinalPosition(r)
        colorAnim?.cancel()
        focusSpring.spring = SpringForce(target).setDampingRatio(1f).setStiffness(k * 0.9f)
        focusSpring.animateToFinalPosition(target)
    }

    private fun fadeFocus(target: Float, d: Long) {
        focusSpring.cancel()
        colorAnim?.cancel()
        colorAnim = ValueAnimator.ofFloat(focusHolder.value, target).apply {
            duration = d
            addUpdateListener {
                focusHolder.value = it.animatedValue as Float
                frame()
            }
            start()
        }
    }

    private fun frame() {
        invalidate()
        onFrame?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        if (!placed) return
        val h = height.toFloat()
        val inset = Ui.dpf(context, 1f)
        val r = (h - 2 * inset) / 2f
        val f = focusMix.coerceIn(0f, 1f)

        // Faint marker on the active page (stays put while the focus pill travels).
        val awayness = ((kotlin.math.abs((left + right) / 2f - (activeL + activeR) / 2f)) / (activeR - activeL).coerceAtLeast(1f)).coerceIn(0f, 1f)
        if (awayness > 0.02f) {
            marker.color = Ui.withAlpha(Color.WHITE, 0.10f * awayness)
            rect.set(activeL, inset, activeR, h - inset)
            canvas.drawRoundRect(rect, r, r, marker)
        }

        // The pill: resting = soft glass, focused = bright accent-tinted glass with lift and edge highlight.
        val rest = Ui.withAlpha(Color.WHITE, 0.16f)
        val lit = Ui.withAlpha(Ui.blend(accent, Color.WHITE, 0.62f), 0.95f)
        fill.color = blendArgb(rest, lit, f)
        if (!lightweight && Build.VERSION.SDK_INT >= 28) {
            fill.setShadowLayer(Ui.dpf(context, 6f) * f, 0f, Ui.dpf(context, 1.5f) * f, Ui.withAlpha(Color.BLACK, 0.35f * f))
        } else fill.clearShadowLayer()
        rect.set(left, inset, right, h - inset)
        canvas.drawRoundRect(rect, r, r, fill)

        if (!lightweight) {
            edge.shader = LinearGradient(0f, inset, 0f, h - inset,
                Ui.withAlpha(Color.WHITE, 0.22f + 0.45f * f), Ui.withAlpha(Color.WHITE, 0.04f), Shader.TileMode.CLAMP)
            val e = edge.strokeWidth / 2f
            rect.set(left + e, inset + e, right - e, h - inset - e)
            canvas.drawRoundRect(rect, r - e, r - e, edge)
        }
    }

    companion object {
        fun blendArgb(a: Int, b: Int, t: Float): Int {
            val u = 1 - t
            return Color.argb(
                (Color.alpha(a) * u + Color.alpha(b) * t).toInt(),
                (Color.red(a) * u + Color.red(b) * t).toInt(),
                (Color.green(a) * u + Color.green(b) * t).toInt(),
                (Color.blue(a) * u + Color.blue(b) * t).toInt(),
            )
        }
    }
}
