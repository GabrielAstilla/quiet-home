package io.github.gabrielastilla.quiethome

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.RecyclerView

/** Cards for the home carousel and the All apps grid. */
class AppAdapter(
    private val repo: AppRepository,
    private val listener: Listener,
) : RecyclerView.Adapter<AppAdapter.Holder>() {

    interface Listener {
        fun onAppClick(entry: AppEntry, position: Int)
        fun onAppLongClick(entry: AppEntry, position: Int)
        fun onAppFocused(entry: AppEntry, position: Int)

        /** Up pressed on the first row. Return true if handled (e.g. focus moved to the tabs). */
        fun onUpFromTopRow(): Boolean = false
    }

    lateinit var style: TileStyle

    private val items = ArrayList<AppEntry>()
    var pinnedKeys: Set<String> = emptySet()
    var showPinDots = false
    private var movingKey: String? = null

    /** Replaces the list. Returns false (and does nothing) when nothing visible changed. */
    fun submit(list: List<AppEntry>): Boolean {
        val same = list.size == items.size && list.indices.all {
            list[it].key == items[it].key && list[it].label == items[it].label
        }
        if (same) return false
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
        return true
    }

    fun keys(): List<String> = items.map { it.key }
    fun entryAt(p: Int): AppEntry? = items.getOrNull(p)
    fun indexOf(key: String): Int = items.indexOfFirst { it.key == key }

    fun move(from: Int, to: Int) {
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }

    fun setMoving(position: Int) {
        val old = movingKey?.let { indexOf(it) } ?: -1
        movingKey = items.getOrNull(position)?.key
        if (old >= 0) notifyItemChanged(old, STATE)
        if (position >= 0) notifyItemChanged(position, STATE)
    }

    /** Re-renders one app's artwork (after custom artwork changed). */
    fun refreshApp(key: String) {
        val i = indexOf(key)
        if (i >= 0) notifyItemChanged(i)
    }

    /** Rebinds pin dots / focus visuals only, without reloading artwork. */
    fun refreshState() {
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size, STATE)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) holder.bind(items[position]) else holder.bindState(items[position], animate = true)
    }

    override fun onViewRecycled(holder: Holder) = holder.recycle()

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val tile: FrameLayout = view.findViewById(R.id.tile)
        private val card: FrameLayout = view.findViewById(R.id.card)
        private val art: ImageView = view.findViewById(R.id.icon)
        private val label: TextView = view.findViewById(R.id.label)
        private val pinDot: View = view.findViewById(R.id.pin_dot)

        private var boundKey: String? = null
        private var styled: TileStyle? = null
        private var ring: GlassRingDrawable? = null
        private var pressed = false
        private var ringAnim: ValueAnimator? = null
        private var ringOn = false

        // Springs retarget smoothly: rapid presses never queue, they just change the target.
        private val springX = SpringAnimation(view, DynamicAnimation.SCALE_X, 1f)
        private val springY = SpringAnimation(view, DynamicAnimation.SCALE_Y, 1f)
        private val springLift = SpringAnimation(view, DynamicAnimation.TRANSLATION_Y, 0f).apply {
            setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS)
        }

        init {
            card.clipToOutline = true
            view.setOnClickListener { current()?.let { (e, p) -> listener.onAppClick(e, p) } }
            view.setOnLongClickListener {
                pressed = false
                updateVisuals(animate = true)
                current()?.let { (e, p) -> listener.onAppLongClick(e, p) }
                true
            }
            view.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) pressed = false
                updateVisuals(animate = true)
                if (hasFocus) current()?.let { (e, p) -> listener.onAppFocused(e, p) }
            }
            view.setOnKeyListener { _, code, ev -> handleKey(code, ev) }
        }

        private fun current(): Pair<AppEntry, Int>? {
            val p = bindingAdapterPosition
            return if (p in items.indices) items[p] to p else null
        }

        /** Keeps focus inside the cards at the edges so navigation never jumps unexpectedly. */
        private fun handleKey(code: Int, ev: KeyEvent): Boolean {
            val p = bindingAdapterPosition
            if (p < 0) return false
            // OK: compress on press, spring back on release. The click (launch) still fires on
            // release as normal, so feedback never delays opening the app.
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) setPressedVisual(true)
                if (ev.action == KeyEvent.ACTION_UP) setPressedVisual(false)
                return false
            }
            if (code == KeyEvent.KEYCODE_MENU) {
                if (ev.action == KeyEvent.ACTION_UP) current()?.let { (e, pos) -> listener.onAppLongClick(e, pos) }
                return true
            }
            if (ev.action != KeyEvent.ACTION_DOWN) return false
            val cols = style.columns
            val n = items.size
            return when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> p % cols == 0
                KeyEvent.KEYCODE_DPAD_RIGHT -> p % cols == cols - 1 || p == n - 1
                KeyEvent.KEYCODE_DPAD_DOWN -> p / cols == (n - 1) / cols
                KeyEvent.KEYCODE_DPAD_UP -> if (p / cols == 0) listener.onUpFromTopRow() else false
                else -> false
            }
        }

        private fun applyStyle() {
            val s = style
            if (styled === s) return
            styled = s
            itemView.layoutParams = (itemView.layoutParams ?: RecyclerView.LayoutParams(s.tileW + s.spacing, -2)).apply {
                width = s.tileW + s.spacing
            }
            (tile.layoutParams as LinearLayout.LayoutParams).apply {
                width = s.tileW
                height = s.cardH + 2 * s.ringInset
            }
            (card.layoutParams as FrameLayout.LayoutParams).setMargins(s.ringInset, s.ringInset, s.ringInset, s.ringInset)
            (label.layoutParams as LinearLayout.LayoutParams).width = s.tileW
            label.visibility = if (s.showLabels) View.VISIBLE else View.GONE
            Fonts.style(label.context, label)
            card.background = GradientDrawable().apply {
                cornerRadius = s.radius
                setColor(s.cardColor)
            }
            ring = GlassRingDrawable(
                s.accent, s.radius + s.ringInset, s.ringInset.toFloat(), s.radius, s.strokeW.toFloat(), glow = !s.lightweight,
            ).also {
                it.alpha = if (ringOn) 255 else 0
                tile.foreground = it
            }
            // Delicate edge highlight on every card, so artwork reads as a glass-framed tile.
            card.foreground = GradientDrawable().apply {
                cornerRadius = s.radius
                setColor(Color.TRANSPARENT)
                setStroke(maxOf(1, s.strokeW / 3), 0x2EFFFFFF)
            }
            pinDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(s.accent)
                setStroke(s.strokeW / 2 + 1, 0xB3000000.toInt())
            }
            // Adjust the existing forces (never replace a force on a running spring — it would never settle).
            springX.spring.setDampingRatio(0.8f).setStiffness(s.motion.stiffness)
            springY.spring.setDampingRatio(0.8f).setStiffness(s.motion.stiffness)
            springLift.spring.setDampingRatio(0.85f).setStiffness(s.motion.stiffness)
            tile.requestLayout()
        }

        fun bind(e: AppEntry) {
            applyStyle()
            boundKey = e.key
            label.text = e.label
            art.setImageDrawable(null)
            bindState(e, animate = false)
            val s = style
            repo.loadCard(e, s.banners, s.cardW, s.cardH) { bmp ->
                if (boundKey == e.key) art.setImageBitmap(bmp)
            }
        }

        fun bindState(e: AppEntry, animate: Boolean) {
            pinDot.visibility = if (showPinDots && e.key in pinnedKeys) View.VISIBLE else View.GONE
            updateVisuals(animate)
        }

        fun recycle() {
            springX.cancel()
            springY.cancel()
            springLift.cancel()
            itemView.translationY = 0f
            pressed = false
            ringAnim?.cancel()
            itemView.scaleX = 1f
            itemView.scaleY = 1f
        }

        private fun setPressedVisual(down: Boolean) {
            if (pressed == down) return
            pressed = down
            updateVisuals(animate = true)
        }

        private fun updateVisuals(animate: Boolean) {
            val s = style
            val m = s.motion
            val focused = itemView.isFocused
            val moving = boundKey != null && boundKey == movingKey
            val on = focused || moving

            ring?.strokeWidth = (if (moving) s.strokeW * 1.7f else s.strokeW.toFloat())
            label.setTextColor(if (on) Color.WHITE else 0xB3FFFFFF.toInt())
            itemView.translationZ = if (moving) 3f else if (on) 2f else 0f // draw above neighbours while enlarged

            // Scale + lift: one spring each, retargeted from the current value on every change.
            val scale = when {
                moving -> s.focusScale + 0.06f
                on && pressed -> s.focusScale - 0.05f
                on -> s.focusScale
                else -> 1f
            }
            val lift = when {
                moving -> -s.lift * 2f
                on && !pressed -> -s.lift
                else -> 0f
            }
            if (animate && m.full && itemView.isAttachedToWindow) {
                springX.animateToFinalPosition(scale)
                springY.animateToFinalPosition(scale)
                springLift.animateToFinalPosition(lift)
            } else {
                springX.cancel()
                springY.cancel()
                springLift.cancel()
                itemView.scaleX = scale
                itemView.scaleY = scale
                itemView.translationY = if (m.enabled) lift else 0f
            }

            // Border: fades in/out, retargeting from its current alpha.
            val r = ring
            if (r != null && (ringOn != on || !animate)) {
                ringOn = on
                ringAnim?.cancel()
                val to = if (on) 255 else 0
                val d = m.d(if (on) 150 else 110)
                if (!animate || d <= 0L) {
                    r.alpha = to
                } else {
                    ringAnim = ValueAnimator.ofInt(r.alpha, to).apply {
                        duration = d
                        interpolator = Motion.EASE_OUT
                        addUpdateListener { r.alpha = it.animatedValue as Int }
                        start()
                    }
                }
            }

            // Soft shadow under the selected card (deeper while being moved).
            val z = if (moving) s.elevation * 1.6f else if (on) s.elevation else 0f
            card.animate().cancel()
            if (animate && m.enabled) card.animate().translationZ(z).setDuration(m.d(180)).start() else card.translationZ = z
        }
    }

    companion object {
        private val STATE = Any()
    }
}
