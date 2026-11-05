package com.zguy.tvlauncher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Floating glass menu used for app options, confirmations and pickers.
 * Opens next to its anchor (the selected card) with a soft scale + fade, gently dims the screen,
 * keeps focus inside itself, closes on Back, reverses its animation, and returns focus to
 * whatever opened it.
 */
class GlassMenu(
    private val activity: Activity,
    /** Full-screen FrameLayout the menu is added to. */
    private val host: FrameLayout,
    /** The screen's main content, blocked from focus while the menu is open. */
    private val content: ViewGroup,
) {
    class Item(val label: String, val action: () -> Unit)

    private var scrim: View? = null
    private var panel: GlassView? = null
    private var restoreFocus: View? = null
    private var onClosed: (() -> Unit)? = null
    private val prefs = activity.launcher.prefs

    val isShowing get() = panel != null

    fun show(
        anchor: View?,
        title: String?,
        items: List<Item>,
        message: String? = null,
        focusIndex: Int = 0,
        onDismiss: (() -> Unit)? = null,
        /** Optional input (e.g. a text field) shown above the items; it receives focus first. */
        field: View? = null,
    ) {
        removeNow()
        val ctx = activity
        val m = Motion.of(prefs)
        restoreFocus = activity.currentFocus
        onClosed = onDismiss
        GlassView.captureSnapshot(activity, translucent = activity is SettingsActivity || activity is PickImageActivity)

        val dim = View(ctx).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
        }
        host.addView(dim, FrameLayout.LayoutParams(-1, -1))
        scrim = dim

        val glass = GlassView(ctx).apply {
            cornerRadius = Ui.dpf(ctx, 22f)
            opacityBoost = 0.25f
            snapshot = activity.launcher.snapshot
            setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 10))
        }
        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (title != null) column.addView(TextView(ctx).apply {
            text = title
            textSize = 20f
            tag = "display-semibold"
            setTextColor(Color.WHITE)
            setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 4), Ui.dp(ctx, 14), Ui.dp(ctx, 6))
        })
        if (message != null) column.addView(TextView(ctx).apply {
            text = message
            textSize = 15f
            setLineSpacing(0f, 1.15f)
            setTextColor(0xD9FFFFFF.toInt())
            setPadding(Ui.dp(ctx, 14), 0, Ui.dp(ctx, 14), Ui.dp(ctx, 10))
        })
        if (field != null) column.addView(field, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8), Ui.dp(ctx, 10))
        })
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val views = items.mapIndexed { i, item ->
            TextView(ctx).apply {
                text = item.label
                textSize = 17f
                setTextColor(Color.WHITE)
                isFocusable = true
                isClickable = true
                minHeight = Ui.dp(ctx, 46)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(ctx, 14), 0, Ui.dp(ctx, 14), 0)
                background = itemBackground()
                setOnClickListener { pick(item) }
                setOnFocusChangeListener { v, f ->
                    (v as TextView).setTextColor(if (f) 0xFF0B1222.toInt() else Color.WHITE)
                }
                setOnKeyListener { _, code, ev ->
                    when (code) {
                        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                            if (ev.action == KeyEvent.ACTION_UP) dismiss()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                        KeyEvent.KEYCODE_DPAD_UP -> ev.action == KeyEvent.ACTION_DOWN && i == 0 && field == null
                        KeyEvent.KEYCODE_DPAD_DOWN -> ev.action == KeyEvent.ACTION_DOWN && i == items.size - 1
                        else -> false
                    }
                }
            }.also { list.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(ctx, 2) }) }
        }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list)
        }
        val maxH = (host.height * 0.7f).toInt().takeIf { it > 0 } ?: Ui.dp(ctx, 360)
        column.addView(object : FrameLayout(ctx) {
            override fun onMeasure(w: Int, h: Int) =
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        }.apply { addView(scroll) })
        glass.addView(column)
        Fonts.apply(glass)

        val width = Ui.dp(ctx, 340)
        val lp = FrameLayout.LayoutParams(width, -2)
        host.addView(glass, lp)
        panel = glass
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        // Position next to the anchor once measured: above it if there is room, else centred.
        glass.alpha = 0f
        glass.post {
            val hostLoc = IntArray(2).also { host.getLocationInWindow(it) }
            val margin = Ui.dp(ctx, 32)
            var x = (host.width - glass.width) / 2f
            var y = (host.height - glass.height) / 2f
            var pivotY = glass.height / 2f
            if (anchor != null && anchor.isAttachedToWindow) {
                val a = IntArray(2).also { anchor.getLocationInWindow(it) }
                val ax = a[0] - hostLoc[0] + anchor.width / 2f
                val ay = a[1] - hostLoc[1].toFloat()
                x = (ax - glass.width / 2f).coerceIn(margin.toFloat(), (host.width - glass.width - margin).toFloat())
                val gap = Ui.dp(ctx, 14)
                val above = ay - glass.height - gap
                val below = ay + anchor.height + gap
                when {
                    above >= margin -> {
                        y = above
                        pivotY = glass.height.toFloat()
                    }
                    below + glass.height <= host.height - margin -> {
                        y = below
                        pivotY = 0f
                    }
                    else -> {
                        // Not enough room above or below: open beside the card, centred on it.
                        val ax0 = a[0] - hostLoc[0].toFloat()
                        val right = ax0 + anchor.width + gap
                        x = if (right + glass.width <= host.width - margin) right
                        else (ax0 - gap - glass.width).coerceAtLeast(margin.toFloat())
                        y = (ay + anchor.height / 2f - glass.height / 2f)
                            .coerceIn(margin.toFloat(), (host.height - glass.height - margin).toFloat().coerceAtLeast(margin.toFloat()))
                        pivotY = glass.height / 2f
                    }
                }
            }
            glass.x = x
            glass.y = y
            val restY = glass.translationY // final resting offset; the open animation eases into it
            glass.pivotX = glass.width / 2f
            glass.pivotY = pivotY
            val d = m.d(260)
            dim.animate().alpha(0.22f).setDuration(d).start()
            if (m.full && d > 0) {
                glass.scaleX = 0.92f
                glass.scaleY = 0.92f
                glass.translationY = restY + Ui.dpf(ctx, if (pivotY == 0f) -8f else 8f)
                glass.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(restY)
                    .setDuration(d).setInterpolator(Motion.EASE_OUT).withEndAction { glass.invalidate() }.start()
            } else if (d > 0) {
                glass.animate().alpha(1f).setDuration(d).start()
            } else {
                glass.alpha = 1f
            }
            if (field != null) {
                field.requestFocus()
                field.postDelayed({
                    (ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .showSoftInput(field, 0)
                }, 150)
            } else views.getOrNull(focusIndex.coerceIn(0, views.size - 1))?.requestFocus()
        }
    }

    private fun itemBackground(): StateListDrawable {
        val r = Ui.dpf(activity, 14f)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                cornerRadius = r
                setColor(0xF2FFFFFF.toInt())
            })
            addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
    }

    private fun pick(item: Item) {
        // Run the choice before the close callback, so a choice that opens the next step
        // (another menu, a picker) is known to the callback.
        val closed = onClosed
        onClosed = null
        dismiss()
        item.action()
        closed?.invoke()
    }

    /** Closes with the opening animation reversed, then restores focus to the opener. */
    fun dismiss() {
        val glass = panel ?: return
        val dim = scrim
        panel = null
        scrim = null
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        restoreFocus?.takeIf { it.isAttachedToWindow }?.requestFocus()
        val closed = onClosed
        onClosed = null
        val m = Motion.of(prefs)
        val d = m.d(180)
        val remove = {
            host.removeView(glass)
            if (dim != null) host.removeView(dim)
        }
        if (d <= 0) {
            remove()
        } else {
            dim?.animate()?.alpha(0f)?.setDuration(d)?.start()
            val a = glass.animate().alpha(0f).setDuration(d).setInterpolator(Motion.EASE_IN)
            if (m.full) a.scaleX(0.95f).scaleY(0.95f)
            a.withEndAction(remove).start()
        }
        closed?.invoke()
    }

    private fun removeNow() {
        panel?.let { host.removeView(it) }
        scrim?.let { host.removeView(it) }
        panel = null
        scrim = null
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    companion object {
        /** A small glass chip near the bottom of the screen, e.g. "Position saved". Non-blocking. */
        fun chip(activity: Activity, host: FrameLayout, text: String) {
            val ctx = activity
            val m = Motion.of(activity.launcher.prefs)
            val glass = GlassView(ctx).apply {
                cornerRadius = Ui.dpf(ctx, 20f)
                opacityBoost = 0.2f
                setPadding(Ui.dp(ctx, 20), Ui.dp(ctx, 10), Ui.dp(ctx, 20), Ui.dp(ctx, 10))
                addView(TextView(ctx).apply {
                    this.text = text
                    textSize = 16f
                    tag = "text-medium"
                    setTextColor(Color.WHITE)
                })
                Fonts.apply(this)
            }
            host.addView(glass, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = Ui.dp(ctx, 26)
            })
            val inD = m.d(220)
            glass.alpha = 0f
            glass.translationY = if (m.full) -Ui.dpf(ctx, 10f) else 0f
            glass.animate().alpha(1f).translationY(0f).setDuration(inD).setInterpolator(Motion.EASE_OUT).start()
            glass.postDelayed({
                glass.animate().alpha(0f).setDuration(m.d(260)).withEndAction { host.removeView(glass) }.start()
                if (!m.enabled) host.removeView(glass)
            }, 1600)
        }
    }
}
