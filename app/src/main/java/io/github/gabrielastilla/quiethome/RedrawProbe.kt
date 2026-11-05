package io.github.gabrielastilla.quiethome

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup

/**
 * Debug-only profiling aid: logs layout passes and draws per second ("QH-Probe"), and which views
 * requested layout, so idle redraw loops and layout thrash can be measured on a device.
 */
object RedrawProbe {
    fun attach(root: View, name: String) {
        var layouts = 0
        var draws = 0
        val h = Handler(Looper.getMainLooper())
        root.viewTreeObserver.addOnGlobalLayoutListener { layouts++ }
        val dirty = HashMap<String, Int>()
        root.viewTreeObserver.addOnPreDrawListener {
            draws++
            fun visit(x: View) {
                if (x.isDirty && (x !is ViewGroup || x.childCount == 0 || x is GlassView || x is BackdropView)) dirty.merge(name(x), 1, Int::plus)
                if (x is ViewGroup) for (i in 0 until x.childCount) visit(x.getChildAt(i))
            }
            visit(root)
            true
        }
        val tick = object : Runnable {
            override fun run() {
                if (layouts + draws > 0) Log.d("QH-Probe", "$name: layouts=$layouts draws=$draws /s" +
                    (if (layouts > 3) "  requested by: " + requesters(root) else "") +
                    "  pill: " + (root.findViewById<NavPill>(R.id.nav_pill)?.debugState() ?: "-") +
                    "  dirty: " + dirty.entries.sortedByDescending { it.value }.take(5).joinToString { "${it.key}×${it.value}" })
                layouts = 0; draws = 0; dirty.clear()
                h.postDelayed(this, 1000)
            }
        }
        h.postDelayed(tick, 1000)
    }

    private fun requesters(v: View): String {
        val out = ArrayList<String>()
        fun visit(x: View) {
            if (x.isLayoutRequested && x !is ViewGroup) out += name(x)
            if (x is ViewGroup) for (i in 0 until x.childCount) visit(x.getChildAt(i))
        }
        visit(v)
        return out.take(6).joinToString()
    }

    private fun name(v: View) = (if (v.id != View.NO_ID) try { v.resources.getResourceEntryName(v.id) } catch (e: Exception) { null } else null) ?: v.javaClass.simpleName
}
