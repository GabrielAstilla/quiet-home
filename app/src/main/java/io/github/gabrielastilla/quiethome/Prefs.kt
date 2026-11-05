package io.github.gabrielastilla.quiethome

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

data class Choice<T>(val label: String, val value: T)

/**
 * All persistent state, stored locally in SharedPreferences:
 * appearance, background, motion settings, pinned order, per-app image positions, and usage.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences = context.getSharedPreferences("quiet_home", Context.MODE_PRIVATE)
    private val usage: SharedPreferences = context.getSharedPreferences("quiet_home_usage", Context.MODE_PRIVATE)

    // ---- Change notification ----

    /** Bumped on every appearance change, so screens can tell when to restyle. */
    val revision: Int get() = sp.getInt(K_REV, 0)

    private val changeListeners = ArrayList<() -> Unit>()
    private val spListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == K_REV) changeListeners.toList().forEach { it() }
    }

    init {
        sp.registerOnSharedPreferenceChangeListener(spListener)
    }

    /** Called on the main thread whenever an appearance setting changes (used for live preview). */
    fun addChangeListener(l: () -> Unit) {
        changeListeners += l
    }

    fun removeChangeListener(l: () -> Unit) {
        changeListeners -= l
    }

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        val e = sp.edit()
        e.block()
        e.putInt(K_REV, revision + 1)
        e.apply()
    }

    /** Marks appearance as changed without changing a value (e.g. an image file was replaced). */
    fun touch() = edit { }

    private inner class IntPref(private val key: String, private val def: Int) : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) = edit { putInt(key, value) }
    }

    private inner class BoolPref(private val key: String, private val def: Boolean) : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) = edit { putBoolean(key, value) }
    }

    // ---- Pinned apps ----

    var pinned: List<String>
        get() = sp.getString(K_PINNED, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(value) {
            sp.edit().putString(K_PINNED, value.distinct().joinToString("\n")).apply()
        }

    val hasPinnedList: Boolean get() = sp.contains(K_PINNED)

    fun resetPinned() {
        sp.edit().remove(K_PINNED).apply()
    }

    // ---- Cards ----

    var accentColor by IntPref("accent", DEF_ACCENT)
    var cardWidthDp by IntPref("card_dp", 208)
    var spacingDp by IntPref("spacing_dp", 20)
    var cornerDp by IntPref("corner_dp", 14)
    var showLabels by BoolPref("labels", false)
    var useBanners by BoolPref("banners", true)

    // ---- Home screen ----

    var showClock by BoolPref("clock", true)
    var showNetwork by BoolPref("network", true)
    var showDescription by BoolPref("description", true)
    var showDetails by BoolPref("details", true)
    var showHints by BoolPref("hints", true)

    // ---- Clock & greeting ----

    var displayName: String
        get() = sp.getString("display_name", DEF_NAME) ?: DEF_NAME
        set(v) = edit { putString("display_name", v.trim()) }
    var showGreeting by BoolPref("greeting", true)
    var greetingName by BoolPref("greeting_name", true)
    var clockFormat by IntPref("clock_format", CLOCK_12)
    var clockGlass by BoolPref("clock_glass", false)

    // ---- Typography ----

    var useCustomFonts by BoolPref("custom_fonts", true)

    /** Extra margin on every edge, as a percentage of the screen, for TVs that overscan. */
    var safeMarginPct by IntPref("safe_margin", 0)
    var textScalePct by IntPref("text_scale", 100)

    // ---- Video wallpaper & screensaver ----

    var videoPath: String
        get() = sp.getString("video_path", "") ?: ""
        set(v) = edit { putString("video_path", v) }
    var animatedWallpaper by BoolPref("animated", true)
    var screensaverIdleMin by IntPref("ss_idle", 10)
    var screensaverSource by IntPref("ss_source", SS_WALLPAPER)
    var screensaverVideo: String
        get() = sp.getString("ss_video", "") ?: ""
        set(v) = edit { putString("ss_video", v) }
    var screensaverClock by BoolPref("ss_clock", true)
    var screensaverGreeting by BoolPref("ss_greeting", true)

    // ---- Updates ----

    var autoUpdateCheck by BoolPref("auto_update", false)
    var lastUpdateCheck: Long
        get() = usage.getLong("last_update_check", 0L)
        set(v) {
            usage.edit().putLong("last_update_check", v).apply()
        }

    /** Debug builds only: point the updater at a local test server / another repo (ignored in release builds). */
    val debugUpdateApi: String get() = sp.getString("debug_update_api", "") ?: ""
    val debugUpdateRepo: String get() = sp.getString("debug_update_repo", "") ?: ""

    // ---- Glass ----

    /** Full = blurred glass, glow, reflections, shadows. Lightweight = flat translucent surfaces. */
    var effects by IntPref("effects", EFFECTS_FULL)
    var glassBlur by IntPref("glass_blur", 2)
    var glassOpacity by IntPref("glass_opacity", 30)
    var glassTint by IntPref("glass_tint", 0xFF0E1424.toInt())

    val lightweight: Boolean get() = effects == EFFECTS_LIGHT

    // ---- Motion ----

    var motionLevel by IntPref("motion", MOTION_FULL)
    var animSpeedPct by IntPref("speed", 100)

    // ---- All apps ----

    var columns by IntPref("columns", 6)
    var includeNonTvApps by BoolPref("non_tv", false)

    // ---- Background ----

    var bgType by IntPref("bg_type", BG_GRADIENT)
    var bgSolid by IntPref("bg_solid", 0xFF0B1222.toInt())
    var gradTop by IntPref("grad_top", 0xFF13284F.toInt())
    var gradBottom by IntPref("grad_bottom", 0xFF050A18.toInt())
    var gradDir by IntPref("grad_dir", DIR_DIAGONAL)
    var wallpaperPosition by IntPref("wp_pos", POS_CROP)
    var perAppMode by IntPref("per_app", PER_APP_CUSTOM_AND_ART)
    var brightness by IntPref("brightness", 100)
    var dimPct by IntPref("dim", 0)
    var blur by IntPref("blur", BLUR_OFF)
    var overlayColor by IntPref("overlay_color", 0xFF0A1838.toInt())
    var overlayOpacity by IntPref("overlay_opacity", 40)

    fun appBgPosition(key: String): Int = sp.getInt("pos:$key", POS_CROP)
    fun setAppBgPosition(key: String, pos: Int) = edit { putInt("pos:$key", pos) }

    // ---- Usage (local only, shown in the hero details) ----

    fun recordLaunch(key: String) {
        usage.edit().putLong("t:$key", System.currentTimeMillis()).apply()
    }

    fun lastLaunch(key: String): Long = usage.getLong("t:$key", 0L)

    // ---- Resets ----

    fun resetBackground() = edit { BACKGROUND_KEYS.forEach { remove(it) } }

    fun resetAll() = edit {
        (BACKGROUND_KEYS + APPEARANCE_KEYS).forEach { remove(it) }
        sp.all.keys.filter { it.startsWith("pos:") }.forEach { remove(it) }
    }

    companion object {
        private const val K_REV = "rev"
        private const val K_PINNED = "pinned"

        private val BACKGROUND_KEYS = listOf(
            "bg_type", "bg_solid", "grad_top", "grad_bottom", "grad_dir", "wp_pos", "per_app",
            "brightness", "dim", "blur", "overlay_color", "overlay_opacity", "animated",
        )
        private val APPEARANCE_KEYS = listOf(
            "accent", "card_dp", "spacing_dp", "corner_dp", "labels", "banners", "clock", "network",
            "description", "details", "hints", "motion", "speed", "columns", "non_tv",
            "effects", "glass_blur", "glass_opacity", "glass_tint",
            "display_name", "greeting", "greeting_name", "clock_format", "clock_glass",
            "custom_fonts", "text_scale", "ss_idle", "ss_source", "ss_clock", "ss_greeting", "safe_margin",
        )

        const val DEF_NAME = "Gabriel"
        const val CLOCK_12 = 0
        const val CLOCK_12_AMPM = 1
        const val CLOCK_24 = 2
        const val SS_WALLPAPER = 0
        const val SS_VIDEO = 1
        const val BG_VIDEO = 3

        val CLOCK_FORMATS = listOf(Choice("12-hour (9:41)", CLOCK_12), Choice("12-hour with AM/PM", CLOCK_12_AMPM), Choice("24-hour (21:41)", CLOCK_24))
        val SAFE_MARGINS = listOf(Choice("None", 0), Choice("2%", 2), Choice("3%", 3), Choice("5% (full overscan)", 5))
        val TEXT_SCALES = listOf(90, 100, 110, 120, 135).map { Choice("$it%", it) }
        val IDLE_TIMES = listOf(Choice("Off", 0), Choice("1 min", 1), Choice("2 min", 2), Choice("5 min", 5), Choice("10 min", 10), Choice("15 min", 15), Choice("30 min", 30))
        val SS_SOURCES = listOf(Choice("Current wallpaper", SS_WALLPAPER), Choice("Screensaver video", SS_VIDEO))

        val DEF_ACCENT = 0xFFFFFFFF.toInt()

        const val MOTION_FULL = 0
        const val MOTION_REDUCED = 1
        const val MOTION_OFF = 2

        const val BG_SOLID = 0
        const val BG_GRADIENT = 1
        const val BG_IMAGE = 2

        const val DIR_VERTICAL = 0
        const val DIR_DIAGONAL = 1
        const val DIR_HORIZONTAL = 2

        const val POS_FILL = 0
        const val POS_FIT = 1
        const val POS_CROP = 2

        const val PER_APP_OFF = 0
        const val PER_APP_CUSTOM = 1
        const val PER_APP_CUSTOM_AND_ART = 2

        const val BLUR_OFF = 0

        const val EFFECTS_FULL = 0
        const val EFFECTS_LIGHT = 1

        val EFFECTS = listOf(Choice("Full glass", EFFECTS_FULL), Choice("Lightweight", EFFECTS_LIGHT))
        val GLASS_BLUR = listOf(Choice("Off (translucent)", 0), Choice("Low", 1), Choice("Medium", 2), Choice("High", 3))
        val GLASS_TINTS = listOf(
            Choice("Smoke", 0xFF0E1424.toInt()),
            Choice("Frost", 0xFFFFFFFF.toInt()),
            Choice("Navy", 0xFF14254A.toInt()),
            Choice("Graphite", 0xFF2A2D33.toInt()),
            Choice("Teal", 0xFF0F3A3A.toInt()),
            Choice("Plum", 0xFF2E1838.toInt()),
        )

        val ACCENTS = listOf(
            Choice("White", 0xFFFFFFFF.toInt()),
            Choice("Ice", 0xFFCFE3FF.toInt()),
            Choice("Sky", 0xFF5B9BFF.toInt()),
            Choice("Teal", 0xFF2EC4B6.toInt()),
            Choice("Green", 0xFF5BD16A.toInt()),
            Choice("Amber", 0xFFFFB020.toInt()),
            Choice("Coral", 0xFFFF6B5B.toInt()),
            Choice("Pink", 0xFFFF5FA2.toInt()),
            Choice("Violet", 0xFFA78BFA.toInt()),
        )

        /** Palette for solid backgrounds and gradient stops. */
        val COLOURS = listOf(
            Choice("Midnight", 0xFF0B1222.toInt()),
            Choice("Night", 0xFF050A18.toInt()),
            Choice("Deep blue", 0xFF13284F.toInt()),
            Choice("Royal", 0xFF1F3A8A.toInt()),
            Choice("Ocean", 0xFF0E3A5A.toInt()),
            Choice("Teal", 0xFF0A2E2E.toInt()),
            Choice("Forest", 0xFF0F2A18.toInt()),
            Choice("Dusk", 0xFF2C2350.toInt()),
            Choice("Plum", 0xFF2A1033.toInt()),
            Choice("Wine", 0xFF3A0F1C.toInt()),
            Choice("Ember", 0xFF4A1E0C.toInt()),
            Choice("Espresso", 0xFF2A1C14.toInt()),
            Choice("Slate", 0xFF2B3445.toInt()),
            Choice("Graphite", 0xFF1C1D21.toInt()),
            Choice("Black", 0xFF000000.toInt()),
        )

        val OVERLAY_COLOURS = listOf(
            Choice("Navy", 0xFF0A1838.toInt()),
            Choice("Ink", 0xFF0B0F1A.toInt()),
            Choice("Black", 0xFF000000.toInt()),
            Choice("Teal", 0xFF062626.toInt()),
            Choice("Forest", 0xFF0A1E10.toInt()),
            Choice("Plum", 0xFF1E0B26.toInt()),
            Choice("Wine", 0xFF260A12.toInt()),
        )

        val BG_TYPES = listOf(
            Choice("Solid colour", BG_SOLID), Choice("Gradient", BG_GRADIENT),
            Choice("Wallpaper image", BG_IMAGE), Choice("Video wallpaper", 3),
        )
        val GRAD_DIRS = listOf(Choice("Vertical", DIR_VERTICAL), Choice("Diagonal", DIR_DIAGONAL), Choice("Horizontal", DIR_HORIZONTAL))
        val POSITIONS = listOf(Choice("Fill (stretch)", POS_FILL), Choice("Fit (whole image)", POS_FIT), Choice("Crop (cover)", POS_CROP))
        val PER_APP = listOf(
            Choice("Off — one background", PER_APP_OFF),
            Choice("Custom images only", PER_APP_CUSTOM),
            Choice("Custom + app artwork", PER_APP_CUSTOM_AND_ART),
        )
        val BRIGHTNESS = (4..10).map { Choice("${it * 10}%", it * 10) }
        val DIM = (0..8).map { Choice("${it * 10}%", it * 10) }
        val OPACITY = (0..9).map { Choice("${it * 10}%", it * 10) }
        val BLUR = listOf(Choice("Off", 0), Choice("Low", 1), Choice("Medium", 2), Choice("High", 3))

        val CARD_SIZES = listOf(144, 160, 176, 192, 208, 224, 240, 272, 304).map { Choice("$it dp", it) }
        val SPACINGS = listOf(8, 12, 16, 20, 24, 32, 40).map { Choice("$it dp", it) }
        val CORNERS = listOf(0, 4, 8, 10, 14, 18, 24).map { Choice("$it dp", it) }
        val COLUMN_CHOICES = (3..10).map { Choice("$it", it) }
        val MOTION = listOf(Choice("Full", MOTION_FULL), Choice("Reduced", MOTION_REDUCED), Choice("Off", MOTION_OFF))
        val SPEEDS = listOf(
            Choice("Slow (0.5×)", 50), Choice("Relaxed (0.75×)", 75), Choice("Normal", 100),
            Choice("Quick (1.25×)", 125), Choice("Fast (1.5×)", 150),
        )
    }
}
