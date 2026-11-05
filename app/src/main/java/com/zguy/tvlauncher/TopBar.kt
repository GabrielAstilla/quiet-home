package com.zguy.tvlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextClock
import android.widget.TextView

/**
 * Top navigation (HOME · ALL APPS · SETTINGS) plus clock and network status.
 * Down from any tab calls [onDown], which returns focus to the screen's cards.
 */
class TopBar(private val activity: Activity, private val current: Int) {

    private val prefs = activity.launcher.prefs
    val home: TextView = activity.findViewById(R.id.tab_home)
    val apps: TextView = activity.findViewById(R.id.tab_apps)
    val settings: TextView = activity.findViewById(R.id.tab_settings)
    private val clock: TextClock = activity.findViewById(R.id.clock)
    private val net: ImageView = activity.findViewById(R.id.net)
    private val navGlass: GlassView = activity.findViewById(R.id.nav_glass)
    private val statusGlass: GlassView = activity.findViewById(R.id.status_glass)
    private val greeting: TextView = activity.findViewById(R.id.greeting)
    val clockGreeting = ClockGreeting(activity, clock, greeting)
    private val tabs = listOf(home, apps, settings)
    private val pill: NavPill = activity.findViewById(R.id.nav_pill)
    private var focusedTab = -1
    private val cm = activity.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    var onDown: (() -> Boolean)? = null
    var onFocusChanged: ((Boolean) -> Unit)? = null

    val currentTab: View get() = tabs[current]

    init {
        navGlass.capsule = true
        statusGlass.cornerRadius = Ui.dpf(activity, 22f)
        // Over a bare wallpaper the clock and greeting rely on soft shadows for contrast.
        val sh = 0x73000000
        clock.setShadowLayer(Ui.dpf(activity, 12f), 0f, Ui.dpf(activity, 1.5f), sh)
        greeting.setShadowLayer(Ui.dpf(activity, 8f), 0f, Ui.dpf(activity, 1f), sh)
        home.setOnClickListener { if (current != HOME) activity.finish() } // reverse page transition (see finish())
        apps.setOnClickListener {
            if (current != APPS) Ui.startPage(activity, Intent(activity, AllAppsActivity::class.java), forward = true)
        }
        settings.setOnClickListener { Ui.openSettings(activity) }
        (tabs[0].parent as View).addOnLayoutChangeListener { _, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol) placeActive() }
        tabs.forEachIndexed { i, t ->
            t.setOnFocusChangeListener { _, has ->
                if (has) focusedTab = i else if (focusedTab == i) focusedTab = -1
                // Post so a focus hop between two tabs moves the pill once, straight to the new tab,
                // instead of first returning to the active page.
                t.post { movePill() }
                onFocusChanged?.invoke(tabs.any { it.isFocused })
            }
            t.setOnKeyListener { _, code, ev ->
                when {
                    ev.action != KeyEvent.ACTION_DOWN -> false
                    code == KeyEvent.KEYCODE_DPAD_DOWN -> onDown?.invoke() ?: false
                    code == KeyEvent.KEYCODE_DPAD_LEFT && i == 0 -> true
                    code == KeyEvent.KEYCODE_DPAD_RIGHT && i == tabs.size - 1 -> true
                    else -> false
                }
            }
        }
    }

    fun contains(v: View?) = v != null && tabs.any { it === v }

    /** Focus pill: on the focused tab while the navigation has focus, otherwise resting on the active page. */
    private fun movePill() {
        val f = tabs.indexOfFirst { it.isFocused }
        val i = if (f >= 0) f else current
        val t = tabs[i]
        if (t.width == 0) return
        val off = (t.parent as View).left // labels container's offset inside the glass
        pill.moveTo((off + t.left).toFloat(), (off + t.right).toFloat(), focused = f >= 0, motion = Motion.of(prefs))
    }

    private fun placeActive() {
        // The pill exactly covers the labels row (it must not size the capsule itself).
        val row = tabs[0].parent as View
        val lp = pill.layoutParams
        if (row.width > 0 && (lp.width != row.width || lp.height != row.height)) {
            lp.width = row.width
            lp.height = row.height
            pill.layoutParams = lp
        }
        val t = tabs[current]
        val off = (t.parent as View).left
        pill.setActive((off + t.left).toFloat(), (off + t.right).toFloat())
        movePill()
    }

    /** Labels never move; their colour follows how much of the bright focus pill sits under them. */
    private fun recolor() {
        val off = ((tabs[0].parent as View).left).toFloat()
        val l = pill.left
        val r = pill.right
        val f = pill.focusMix.coerceIn(0f, 1f)
        tabs.forEachIndexed { i, t ->
            val tl = off + t.left
            val tr = off + t.right
            val overlap = ((minOf(r, tr) - maxOf(l, tl)) / (tr - tl).coerceAtLeast(1f)).coerceIn(0f, 1f)
            val base = if (i == current) Color.WHITE else 0xB3FFFFFF.toInt()
            t.setTextColor(NavPill.blendArgb(base, 0xFF0B1222.toInt(), overlap * f))
        }
    }

    fun applyStyle() {
        tabs.forEach { it.background = null }
        pill.setStyle(prefs.accentColor, prefs.lightweight)
        pill.onFrame = { recolor() }
        (tabs[0].parent as View).post { placeActive() }
        clockGreeting.applyStyle()
        net.visibility = if (prefs.showNetwork) View.VISIBLE else View.GONE
        val any = prefs.showClock || prefs.showNetwork || prefs.showGreeting
        statusGlass.visibility = if (any) View.VISIBLE else View.INVISIBLE
        (net.layoutParams as android.widget.LinearLayout.LayoutParams).marginEnd = if (prefs.showClock) Ui.dp(activity, 14) else 0
        statusGlass.plain = !prefs.clockGlass
        navGlass.refreshStyle()
        statusGlass.refreshStyle()
        Fonts.apply(navGlass)
        Fonts.apply(statusGlass)
        if (prefs.showNetwork) updateNet()
    }


    /** Network status updates only while the screen is visible. */
    fun onResume() {
        applyStyle()
        clockGreeting.start()
        if (!prefs.showNetwork || callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = post()
            override fun onLost(network: Network) = post()
            private fun post() {
                net.post { updateNet() }
            }
        }
        try {
            cm?.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (e: Exception) {
            // Status icon simply stays static.
        }
    }

    fun onPause() {
        clockGreeting.stop()
        callback?.let {
            try {
                cm?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
            }
        }
        callback = null
    }

    private fun updateNet() {
        val caps = try {
            cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        } catch (e: Exception) {
            null
        }
        when {
            caps == null -> {
                net.setImageResource(R.drawable.ic_wifi)
                net.alpha = 0.3f
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                net.setImageResource(R.drawable.ic_ethernet)
                net.alpha = 1f
            }
            else -> {
                net.setImageResource(R.drawable.ic_wifi)
                net.alpha = 1f
            }
        }
    }

    companion object {
        const val HOME = 0
        const val APPS = 1
    }
}
