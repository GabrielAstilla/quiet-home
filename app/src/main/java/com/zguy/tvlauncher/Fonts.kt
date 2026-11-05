package com.zguy.tvlauncher

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File

/**
 * Typography, in order of preference:
 *   1. SF Pro — Apple's font; its licence only covers mock-ups for Apple platforms, so it is NOT bundled.
 *      Used if you import licensed files ("Display" for titles/clock, "Text" for labels; Regular/Medium/Semibold).
 *   2. Helvetica / Helvetica Neue — commercial (Monotype), also NOT bundled; used if you import licensed files.
 *   3. TeX Gyre Heros — a free Helvetica-style font (GUST Font License, see assets/fonts), bundled as the default.
 *   4. Roboto — the TV's system font, if the bundled files can't be read.
 */
object Fonts {

    enum class Role { DISPLAY, TEXT }
    enum class Weight { REGULAR, MEDIUM, SEMIBOLD }

    private val cache = HashMap<String, Typeface>()
    private var loadedFrom: String? = null
    private val custom = HashMap<String, Typeface>() // "SF-DISPLAY-MEDIUM" / "HELV-ANY-MEDIUM" -> face
    private var heros: Pair<Typeface?, Typeface?>? = null // bundled regular, bold

    /** Where you copy font files (adb push …/fonts/). */
    fun importDir(ctx: Context): File? = ctx.getExternalFilesDir("fonts")

    private fun privateDir(ctx: Context) = File(ctx.filesDir, "fonts").apply { mkdirs() }

    /** Human-readable status for Settings. */
    fun status(ctx: Context): String {
        ensureLoaded(ctx)
        return when {
            !ctx.launcher.prefs.useCustomFonts -> "System font (Roboto)"
            custom.keys.any { it.startsWith("SF-") } -> "SF Pro (${custom.keys.count { it.startsWith("SF-") }} faces)"
            custom.keys.any { it.startsWith("HELV-") } -> "Helvetica (${custom.keys.count { it.startsWith("HELV-") }} faces)"
            bundled(ctx).first != null -> "TeX Gyre Heros (Helvetica-style, built in)"
            else -> "Roboto (fallback)"
        }
    }

    /**
     * Copies font files from the import folder into private storage. Files are matched by name:
     * containing "Display" or "Text", and "Regular", "Medium" or "Semibold". Returns faces imported.
     */
    fun importFromFolder(ctx: Context): Int {
        val src = importDir(ctx)?.listFiles()?.filter { it.isFile && it.name.lowercase().let { n -> n.endsWith(".otf") || n.endsWith(".ttf") } }
            .orEmpty()
        var n = 0
        for (f in src) {
            val key = keyFor(f.name) ?: continue
            // Validate before keeping: a broken file must never crash text rendering.
            val ok = runCatching { Typeface.createFromFile(f) }.isSuccess
            if (!ok) continue
            f.copyTo(File(privateDir(ctx), "$key.${f.extension.lowercase()}"), overwrite = true)
            n++
        }
        reload(ctx)
        return n
    }

    fun removeCustom(ctx: Context) {
        privateDir(ctx).listFiles()?.forEach { it.delete() }
        reload(ctx)
    }

    private fun keyFor(name: String): String? {
        val n = name.lowercase().replace("-", "").replace("_", "").replace(" ", "")
        if ("italic" in n || "oblique" in n) return null
        if ("helvetica" in n) {
            // Helvetica Neue ships Roman/Medium/Bold; Bold stands in for Semibold.
            val w = when {
                "semibold" in n || "bold" in n && "extrabold" !in n && "ultrabold" !in n -> Weight.SEMIBOLD
                "medium" in n -> Weight.MEDIUM
                "roman" in n || "regular" in n || n.removeSuffix(".ttf").removeSuffix(".otf").let { it == "helvetica" || it == "helveticaneue" } -> Weight.REGULAR
                else -> return null
            }
            return "HELV-ANY-${w.name}"
        }
        val role = when {
            "display" in n -> Role.DISPLAY
            "text" in n -> Role.TEXT
            else -> return null
        }
        val weight = when {
            "semibold" in n -> Weight.SEMIBOLD
            "medium" in n -> Weight.MEDIUM
            "regular" in n -> Weight.REGULAR
            else -> return null
        }
        return "SF-${role.name}-${weight.name}"
    }

    private fun bundled(ctx: Context): Pair<Typeface?, Typeface?> {
        heros?.let { return it }
        val r = runCatching { Typeface.createFromAsset(ctx.assets, "fonts/texgyreheros-regular.otf") }.getOrNull()
        val b = runCatching { Typeface.createFromAsset(ctx.assets, "fonts/texgyreheros-bold.otf") }.getOrNull()
        return (r to b).also { heros = it }
    }

    private fun reload(ctx: Context) {
        loadedFrom = null
        cache.clear()
        ensureLoaded(ctx)
    }

    private fun ensureLoaded(ctx: Context) {
        val dir = privateDir(ctx)
        val stamp = dir.listFiles()?.joinToString { it.name + it.lastModified() } ?: ""
        if (loadedFrom == stamp) return
        loadedFrom = stamp
        custom.clear()
        dir.listFiles()?.forEach { f ->
            runCatching { custom[f.nameWithoutExtension] = Typeface.createFromFile(f) }
        }
    }

    fun typeface(ctx: Context, role: Role, weight: Weight): Typeface {
        ensureLoaded(ctx)
        val useCustom = ctx.launcher.prefs.useCustomFonts
        val k = "$useCustom-$role-$weight"
        cache[k]?.let { return it }
        val tf = (if (useCustom) pickCustom(role, weight) ?: pickBundled(ctx, weight) else null) ?: system(weight)
        cache[k] = tf
        return tf
    }

    private fun order(weight: Weight) = when (weight) {
        Weight.REGULAR -> listOf(Weight.REGULAR, Weight.MEDIUM, Weight.SEMIBOLD)
        Weight.MEDIUM -> listOf(Weight.MEDIUM, Weight.REGULAR, Weight.SEMIBOLD)
        Weight.SEMIBOLD -> listOf(Weight.SEMIBOLD, Weight.MEDIUM, Weight.REGULAR)
    }

    /** SF Pro if any imported (same role first, nearest weight), else imported Helvetica. */
    private fun pickCustom(role: Role, weight: Weight): Typeface? {
        val other = if (role == Role.DISPLAY) Role.TEXT else Role.DISPLAY
        if (custom.keys.any { it.startsWith("SF-") }) {
            for (r in listOf(role, other)) for (w in order(weight)) custom["SF-${r.name}-${w.name}"]?.let { return it }
        }
        for (w in order(weight)) custom["HELV-ANY-${w.name}"]?.let { return it }
        return null
    }

    /** Bundled TeX Gyre Heros has Regular and Bold: Regular/Medium → Regular, Semibold → Bold. */
    private fun pickBundled(ctx: Context, weight: Weight): Typeface? {
        val (r, b) = bundled(ctx)
        return if (weight == Weight.SEMIBOLD) b ?: r else r
    }

    private fun system(weight: Weight): Typeface {
        val w = when (weight) {
            Weight.REGULAR -> 400
            Weight.MEDIUM -> 500
            Weight.SEMIBOLD -> 600
        }
        return if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, w, false)
        else when (weight) {
            Weight.REGULAR -> Typeface.create("sans-serif", Typeface.NORMAL)
            Weight.MEDIUM -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            Weight.SEMIBOLD -> Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    }

    /**
     * Applies the typography to every TextView under [root]. A view's role/weight comes from its
     * tag ("display-semibold", "text-medium", …) or, if untagged, from its size and boldness.
     * Text size is scaled by the Text size setting from each view's original size, and fallback
     * line spacing is enabled so taller fonts never clip.
     */
    fun apply(root: View) {
        val ctx = root.context
        val scale = ctx.launcher.prefs.textScalePct / 100f
        fun visit(v: View) {
            if (v is TextView) style(ctx, v, scale)
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(root)
    }

    fun style(ctx: Context, v: TextView, scale: Float = ctx.launcher.prefs.textScalePct / 100f) {
        val base = (v.getTag(R.id.font_base_size) as? Float) ?: v.textSize.also { v.setTag(R.id.font_base_size, it) }
        val spec = (v.getTag(R.id.font_role) as? String) ?: (v.tag as? String)?.takeIf { it.startsWith("display") || it.startsWith("text") }
            ?: run {
                val sp = base / ctx.resources.displayMetrics.scaledDensity
                val bold = v.typeface?.isBold == true
                val role = if (sp >= 24f) "display" else "text"
                val weight = when {
                    bold -> "semibold"
                    sp >= 40f -> "semibold"
                    else -> "regular"
                }
                "$role-$weight"
            }.also { v.setTag(R.id.font_role, it) }
        val role = if (spec.startsWith("display")) Role.DISPLAY else Role.TEXT
        val weight = when {
            spec.endsWith("semibold") -> Weight.SEMIBOLD
            spec.endsWith("medium") -> Weight.MEDIUM
            else -> Weight.REGULAR
        }
        v.typeface = typeface(ctx, role, weight)
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * scale)
        if (Build.VERSION.SDK_INT >= 28) v.isFallbackLineSpacing = true
        v.includeFontPadding = true
        // Display faces are designed with tighter tracking at large sizes.
        if (role == Role.DISPLAY && v.letterSpacing == 0f) v.letterSpacing = -0.01f
    }
}
