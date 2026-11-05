package io.github.gabrielastilla.quiethome

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.widget.TextClock
import android.widget.TextView

/**
 * Binds a clock and the greeting below it (Home top bar, screensaver, settings preview).
 * The greeting re-checks the local time every minute (ACTION_TIME_TICK, only while visible),
 * on time/zone changes and on resume; when the wording changes it crossfades (instant with motion off).
 */
class ClockGreeting(
    private val ctx: Context,
    private val clock: TextClock?,
    private val greeting: TextView,
    /** Screensaver uses its own toggles. */
    private val forScreensaver: Boolean = false,
) {
    private val prefs = ctx.launcher.prefs
    private var receiver: BroadcastReceiver? = null

    fun applyStyle() {
        clock?.let {
            val (f12, f24) = when (prefs.clockFormat) {
                Prefs.CLOCK_24 -> "HH:mm" to "HH:mm"
                Prefs.CLOCK_12_AMPM -> "h:mm a" to "h:mm a"
                else -> "h:mm" to "h:mm"
            }
            it.format12Hour = f12
            it.format24Hour = f24
            it.visibility = if (clockVisible()) View.VISIBLE else View.GONE
        }
        greeting.visibility = if (greetingVisible()) View.VISIBLE else View.GONE
        refresh(animate = false)
    }

    private fun clockVisible() = if (forScreensaver) prefs.screensaverClock else prefs.showClock
    private fun greetingVisible() = if (forScreensaver) prefs.screensaverGreeting else prefs.showGreeting

    /** Updates the greeting text; crossfades if it changed. */
    fun refresh(animate: Boolean = true) {
        val text = Greeting.text(prefs)
        if (greeting.text.toString() == text) return
        val m = Motion.of(prefs)
        if (!animate || !m.enabled || greeting.text.isNullOrEmpty() || !greeting.isAttachedToWindow) {
            greeting.text = text
            return
        }
        greeting.animate().cancel()
        greeting.animate().alpha(0f).setDuration(m.d(160)).withEndAction {
            greeting.text = text
            greeting.animate().alpha(1f).setDuration(m.d(260)).start()
        }.start()
    }

    fun start() {
        refresh(animate = true)
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = refresh(animate = true)
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ctx.registerReceiver(r, f)
        receiver = r
    }

    fun stop() {
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        receiver = null
    }
}
