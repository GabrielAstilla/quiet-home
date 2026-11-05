package io.github.gabrielastilla.quiethome

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AppEntry(
    /** Stable id: "package/activity". Used for pinning, artwork and backgrounds. */
    val key: String,
    val component: ComponentName,
    val label: String,
    /** True when the app declares a TV (LEANBACK_LAUNCHER) entry point. */
    val isTv: Boolean,
) {
    val packageName: String get() = component.packageName
}

class AppDetails(val version: String?, val updated: Long, val description: String?)

/**
 * Lists launchable apps, renders and caches their card artwork, and launches them.
 * All PackageManager and bitmap work runs off the main thread; callbacks arrive on the main thread.
 */
class AppRepository(private val context: Context, private val prefs: Prefs) {

    private val pm: PackageManager = context.packageManager
    private val main = Handler(Looper.getMainLooper())

    /** Background thread for app queries and file work. */
    val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "qh-worker").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val iconWorker: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "qh-icons").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    var apps: List<AppEntry> = emptyList()
        private set
    var loaded = false
        private set
    private var byKey: Map<String, AppEntry> = emptyMap()
    private val listeners = ArrayList<() -> Unit>()

    /** Card bitmaps, rendered once at the exact card size. */
    private val cardCache = object : LruCache<String, Bitmap>(cacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /** Tiny pre-blurred backdrops made from app artwork (~80 KB each). */
    private val backdropCache = LruCache<String, Bitmap>(48)
    private val details = HashMap<String, AppDetails>()

    private val refreshTask = Runnable { refresh() }

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    fun find(key: String): AppEntry? = byKey[key]

    fun refresh() {
        val includeNonTv = prefs.includeNonTvApps
        worker.execute {
            val list = try {
                query(includeNonTv)
            } catch (e: Exception) {
                null
            }
            main.post {
                if (list != null) {
                    apps = list
                    byKey = list.associateBy { it.key }
                }
                loaded = true
                listeners.toList().forEach { it() }
            }
        }
    }

    /** Install / uninstall / update. Debounced: one update often fires several broadcasts. */
    fun onPackageChanged(pkg: String?) {
        if (pkg != null) invalidatePrefix("$pkg/")
        main.removeCallbacks(refreshTask)
        main.postDelayed(refreshTask, 400)
    }

    /** Drops cached artwork for one app (after custom artwork changes). */
    fun invalidateApp(key: String) = invalidatePrefix(key)

    private fun invalidatePrefix(prefix: String) {
        cardCache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cardCache.remove(it) }
        backdropCache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { backdropCache.remove(it) }
        details.keys.filter { it.startsWith(prefix) }.forEach { details.remove(it) }
    }

    fun invalidateAllArtwork() {
        cardCache.evictAll()
        backdropCache.evictAll()
    }

    private fun query(includeNonTv: Boolean): List<AppEntry> {
        val own = context.packageName
        val out = LinkedHashMap<String, AppEntry>()
        val tvPackages = HashSet<String>()

        val tvIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        for (ri in pm.queryIntentActivities(tvIntent, 0)) {
            val ai = ri.activityInfo ?: continue
            if (ai.packageName == own) continue
            val cn = ComponentName(ai.packageName, ai.name)
            out[cn.flattenToString()] = AppEntry(cn.flattenToString(), cn, ri.loadLabel(pm).toString().trim(), true)
            tvPackages += ai.packageName
        }
        if (includeNonTv) {
            val phoneIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            for (ri in pm.queryIntentActivities(phoneIntent, 0)) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == own || ai.packageName in tvPackages) continue
                val cn = ComponentName(ai.packageName, ai.name)
                val key = cn.flattenToString()
                if (key !in out) out[key] = AppEntry(key, cn, ri.loadLabel(pm).toString().trim(), false)
            }
        }
        return out.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    /** First-run pins: common streaming apps if installed, otherwise the first few TV apps. */
    fun defaultPins(): List<String> {
        val preferred = listOf(
            "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "com.apple.atve.androidtv.appletv", "com.wbd.stream",
            "com.plexapp.android", "org.xbmc.kodi", "com.spotify.tv.android", "com.google.android.youtube.tvmusic",
        )
        val byPackage = apps.groupBy { it.packageName }
        val picks = preferred.mapNotNull { byPackage[it]?.firstOrNull()?.key }
        return if (picks.isNotEmpty()) picks.take(8) else apps.filter { it.isTv }.take(6).map { it.key }
    }

    // ---- Card artwork ----

    fun loadCard(entry: AppEntry, preferBanner: Boolean, width: Int, height: Int, callback: (Bitmap?) -> Unit) {
        val key = "${entry.key}|${if (preferBanner) 'b' else 'i'}|$width"
        cardCache.get(key)?.let {
            callback(it)
            return
        }
        iconWorker.execute {
            val bmp = try {
                renderCard(entry, preferBanner, width, height)
            } catch (e: Throwable) {
                null
            }
            main.post {
                if (bmp != null) cardCache.put(key, bmp)
                callback(bmp)
            }
        }
    }

    /** Custom artwork, then the app's TV banner, then a generated card (tinted gradient, icon, name). */
    private fun renderCard(entry: AppEntry, preferBanner: Boolean, w: Int, h: Int): Bitmap {
        val custom = ImageFiles.appArtwork(context, entry.key)
        if (custom.exists()) {
            BitmapFactory.decodeFile(custom.path)?.let { src ->
                return ImageOps.cover(src, w, h).also { src.recycle() }
            }
        }
        val info = try {
            pm.getActivityInfo(entry.component, 0)
        } catch (e: Exception) {
            null
        }
        if (preferBanner && info != null) {
            info.loadBanner(pm)?.let { return draw(it, w, h) }
        }
        return renderFallback(entry.label, info?.loadIcon(pm), w, h)
    }

    private fun renderFallback(label: String, icon: Drawable?, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val base = icon?.let { dominantColor(it) } ?: nameColor(label)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), shade(base, 0.62f), shade(base, 0.28f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

        val iconSize = (h * 0.46f).toInt()
        val left = (h * 0.2f).toInt()
        val top = (h - iconSize) / 2
        if (icon != null) {
            icon.setBounds(left, top, left + iconSize, top + iconSize)
            icon.draw(c)
        } else {
            val mono = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = iconSize * 0.9f
                typeface = Fonts.typeface(context, Fonts.Role.TEXT, Fonts.Weight.SEMIBOLD)
                textAlign = Paint.Align.CENTER
            }
            val letter = label.firstOrNull()?.uppercase() ?: "?"
            c.drawText(letter, left + iconSize / 2f, top + iconSize * 0.82f, mono)
        }
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = h * 0.15f
            typeface = Fonts.typeface(context, Fonts.Role.TEXT, Fonts.Weight.SEMIBOLD)
        }
        val textLeft = left + iconSize + h * 0.14f
        val avail = w - textLeft - h * 0.12f
        val shown = TextUtils.ellipsize(label, text, avail, TextUtils.TruncateAt.END).toString()
        c.drawText(shown, textLeft, h / 2f + text.textSize * 0.35f, text)
        return bmp
    }

    private fun draw(d: Drawable, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bmp))
        return bmp
    }

    /** Average opaque colour of an icon, used to tint generated cards. */
    private fun dominantColor(d: Drawable): Int {
        val s = 12
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, s, s)
        d.draw(Canvas(bmp))
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        for (y in 0 until s) for (x in 0 until s) {
            val p = bmp.getPixel(x, y)
            val a = Color.alpha(p)
            if (a < 128) continue
            val sat = maxOf(Color.red(p), Color.green(p), Color.blue(p)) - minOf(Color.red(p), Color.green(p), Color.blue(p))
            val wgt = 1 + sat / 32 // favour colourful pixels over white/grey ones
            r += Color.red(p) * wgt; g += Color.green(p) * wgt; b += Color.blue(p) * wgt; n += wgt
        }
        bmp.recycle()
        if (n == 0L) return 0xFF3A4A6A.toInt()
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun nameColor(label: String): Int {
        val palette = intArrayOf(0xFF3A5BA0.toInt(), 0xFF2E8B7A.toInt(), 0xFF8A4FB0.toInt(), 0xFFB0603A.toInt(), 0xFF4A7A3A.toInt())
        return palette[(label.hashCode() and 0x7fffffff) % palette.size]
    }

    private fun shade(c: Int, f: Float) = Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    // ---- Backdrop from artwork ----

    /** A small, heavily blurred image made from the app's artwork, for the hero background. */
    fun loadBackdrop(entry: AppEntry, callback: (Bitmap?) -> Unit) {
        val key = "${entry.key}|bg"
        backdropCache.get(key)?.let {
            callback(it)
            return
        }
        iconWorker.execute {
            val bmp = try {
                val custom = ImageFiles.appArtwork(context, entry.key)
                val source: Bitmap = if (custom.exists()) {
                    BitmapFactory.decodeFile(custom.path, BitmapFactory.Options().apply { inSampleSize = 2 })
                        ?: error("unreadable artwork")
                } else {
                    val info = pm.getActivityInfo(entry.component, 0)
                    val d = info.loadBanner(pm) ?: info.loadIcon(pm)
                    draw(d, 192, if (info.loadBanner(pm) != null) 108 else 192)
                }
                val small = ImageOps.cover(source, 160, 90)
                source.recycle()
                ImageOps.boxBlur(small, 4)
            } catch (e: Throwable) {
                null
            }
            main.post {
                if (bmp != null) backdropCache.put(key, bmp)
                callback(bmp)
            }
        }
    }

    // ---- Details for the hero area ----

    fun loadDetails(entry: AppEntry, callback: (AppDetails) -> Unit) {
        details[entry.key]?.let {
            callback(it)
            return
        }
        iconWorker.execute {
            val d = try {
                val pi = pm.getPackageInfo(entry.packageName, 0)
                val ai = pi.applicationInfo
                val desc = ai?.loadDescription(pm)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: categoryText(ai, entry.isTv)
                AppDetails(shortVersion(pi.versionName), pi.lastUpdateTime, desc)
            } catch (e: Exception) {
                AppDetails(null, 0L, null)
            }
            main.post {
                details[entry.key] = d
                callback(d)
            }
        }
    }

    private fun categoryText(ai: ApplicationInfo?, isTv: Boolean): String {
        if (ai != null && Build.VERSION.SDK_INT >= 26 && ai.category != ApplicationInfo.CATEGORY_UNDEFINED) {
            ApplicationInfo.getCategoryTitle(context, ai.category)?.let { return "$it app" }
        }
        return if (isTv) "TV app" else "Android app"
    }

    private fun shortVersion(v: String?): String? {
        val s = v?.trim()?.takeWhile { it != ' ' && it != '-' && it != '(' } ?: return null
        if (s.isEmpty()) return null
        return "v" + if (s.length > 12) s.take(12) else s
    }

    private fun cacheSizeKb(): Int =
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceIn(4 * 1024, 32 * 1024)

    // ---- Actions ----

    fun launch(activity: Activity, entry: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(if (entry.isTv) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(entry.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            if (prefs.motionLevel != Prefs.MOTION_OFF) {
                activity.startActivity(intent)
            } else {
                activity.startActivity(intent, ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle())
            }
            prefs.recordLaunch(entry.key)
        } catch (e: Exception) {
            Toast.makeText(activity, "Can't open ${entry.label}", Toast.LENGTH_SHORT).show()
        }
    }

    fun openAppInfo(activity: Activity, packageName: String) {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(activity, "App info is not available on this TV", Toast.LENGTH_SHORT).show()
        }
    }
}
