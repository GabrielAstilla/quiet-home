package io.github.gabrielastilla.quiethome

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Remote-friendly setting rows, shared by the Settings panel and the background preview.
 *   Up/Down moves between rows · Left/Right changes a value (shown as ‹ value ›) · OK toggles or runs.
 */
abstract class Row(val title: String) {
    lateinit var view: View
    lateinit var valueView: TextView
    lateinit var swatch: View
    open val adjustable = false
    var visibleWhen: () -> Boolean = { true }
    internal var changed: () -> Unit = {}

    abstract fun value(): String
    open fun swatchColor(): Int? = null
    open fun step(delta: Int) {}
    open fun activate() = step(1)

    fun refresh() {
        view.visibility = if (visibleWhen()) View.VISIBLE else View.GONE
        val v = value()
        valueView.text = if (adjustable) "‹  $v  ›" else v
        val c = swatchColor()
        if (c == null) {
            swatch.visibility = View.GONE
        } else {
            swatch.visibility = View.VISIBLE
            swatch.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                setStroke(2, 0x88FFFFFF.toInt())
            }
        }
    }
}

class ChoiceRow<T>(
    title: String,
    private val options: List<Choice<T>>,
    private val wrap: Boolean,
    private val get: () -> T,
    private val set: (T) -> Unit,
    private val color: ((T) -> Int)? = null,
    /** Optional note shown after the value, e.g. a limit on this screen. */
    private val note: (() -> String?)? = null,
) : Row(title) {
    override val adjustable = true
    private fun index() = options.indexOfFirst { it.value == get() }.coerceAtLeast(0)
    override fun value() = options[index()].label + (note?.invoke()?.let { " · $it" } ?: "")
    override fun swatchColor() = color?.invoke(get())
    override fun step(delta: Int) {
        val i = index() + delta
        val next = if (wrap) i.mod(options.size) else i.coerceIn(0, options.size - 1)
        if (next == index()) return
        set(options[next].value)
        changed()
    }
}

class ToggleRow(title: String, private val get: () -> Boolean, private val set: (Boolean) -> Unit) : Row(title) {
    override val adjustable = true
    override fun value() = if (get()) "On" else "Off"
    override fun step(delta: Int) {
        set(!get())
        changed()
    }
}

class ActionRow(title: String, private val valueText: () -> String = { "" }, private val action: () -> Unit) : Row(title) {
    override fun value() = valueText()
    override fun activate() = action()
}

/** Builds and manages a vertical list of rows inside [container]. */
class RowList(private val activity: Activity, private val container: LinearLayout) {

    val rows = ArrayList<Row>()
    private val headers = ArrayList<Pair<TextView, () -> Boolean>>()
    var onAnyChange: () -> Unit = {}
    private val prefs = activity.launcher.prefs

    // Deferred building: rows past the first screen are created in small batches over the next frames,
    // so a long list doesn't stall the panel's first frame (61 rows measured at ~54 ms to inflate).
    private var deferAfter = Int.MAX_VALUE
    private var ops = 0
    private val pending = ArrayList<() -> Unit>()
    private var accent: Int? = null

    fun deferAfter(count: Int) {
        deferAfter = count
    }

    private fun run(op: () -> Unit) {
        if (ops++ >= deferAfter) pending += op else op()
    }

    /** Builds the deferred rows, [perFrame] at a time, then calls [done]. */
    fun flushDeferred(perFrame: Int = 6, done: () -> Unit = {}) {
        if (pending.isEmpty()) return done()
        container.postOnAnimation {
            repeat(minOf(perFrame, pending.size)) { pending.removeAt(0)() }
            flushDeferred(perFrame, done)
        }
    }

    fun header(text: String, visibleWhen: () -> Boolean = { true }) = run {
        val tv = TextView(activity).apply {
            this.text = text.uppercase()
            textSize = 13f
            letterSpacing = 0.1f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(Ui.dp(context, 6), Ui.dp(context, 18), 0, Ui.dp(context, 6))
        }
        headers += tv to visibleWhen
        container.addView(tv)
        tv.visibility = if (visibleWhen()) View.VISIBLE else View.GONE
        Fonts.apply(tv)
    }

    fun <R : Row> add(row: R, visibleWhen: () -> Boolean = { true }): R {
        run { build(row, visibleWhen) }
        return row
    }

    private fun build(row: Row, visibleWhen: () -> Boolean) {
        val v = activity.layoutInflater.inflate(R.layout.item_setting, container, false)
        row.view = v
        row.valueView = v.findViewById(R.id.value)
        row.swatch = v.findViewById(R.id.swatch)
        row.visibleWhen = visibleWhen
        row.changed = {
            refreshAll()
            onAnyChange()
        }
        v.findViewById<TextView>(R.id.title).text = row.title
        v.setOnClickListener { row.activate() }
        v.setOnKeyListener { _, code, ev ->
            val horizontal = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
            if (!horizontal) return@setOnKeyListener false
            if (row.adjustable && ev.action == KeyEvent.ACTION_DOWN) row.step(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
            true // Left/Right never move focus out of the list
        }
        v.setOnFocusChangeListener { view, f ->
            Ui.focusScale(view, f, Motion.of(prefs), 1.015f)
            row.valueView.setTextColor(if (f) Color.WHITE else 0xB3FFFFFF.toInt())
        }
        rows += row
        container.addView(v)
        accent?.let { v.background = Ui.focusBackground(activity, it, 12) }
        row.refresh()
        Fonts.apply(v)
    }

    /** Adds a non-focusable view (e.g. a live preview) to the list. */
    fun addCustom(v: View) = run { container.addView(v) }

    fun refreshAll() {
        rows.forEach { it.refresh() }
        headers.forEach { (tv, vis) -> tv.visibility = if (vis()) View.VISIBLE else View.GONE }
    }

    fun restyle(accent: Int) {
        this.accent = accent
        for (r in rows) r.view.background = Ui.focusBackground(activity, accent, 12)
    }

    fun firstVisible(): View? = rows.firstOrNull { it.view.visibility == View.VISIBLE }?.view
}
