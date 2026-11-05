package io.github.gabrielastilla.quiethome

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File

/**
 * Process-wide singletons. The app list starts loading here, before the first Activity
 * has inflated its layout, so the home screen fills in as early as possible.
 *
 * No services, alarms, jobs or network: the only listener is a package-change receiver
 * that lives as long as the launcher process does.
 */
class LauncherApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var repo: AppRepository
        private set
    lateinit var updater: Updater
        private set

    private val main = Handler(Looper.getMainLooper())

    /** Blurred picture of the live background, shared by glass surfaces. */
    val glassHub = GlassHub()

    private val existsCache = HashMap<String, Boolean>()
    private var existsRevision = -1

    /**
     * Cached File.exists() for user image/video files checked on the UI thread (per focus change, settings rows).
     * Every save/delete of those files bumps the settings revision, which clears the cache. Measured before:
     * StrictMode disk-read violations on each card focus.
     */
    fun exists(f: java.io.File): Boolean {
        val rev = prefs.revision
        if (rev != existsRevision) {
            existsRevision = rev
            existsCache.clear()
        }
        return existsCache.getOrPut(f.path) {
            val old = android.os.StrictMode.allowThreadDiskReads()
            try { f.exists() } finally { android.os.StrictMode.setThreadPolicy(old) }
        }
    }

    /** Largest card size (dp) that fits the Home carousel on this screen; 0 until Home has measured. */
    var maxFitCardDp = 0

    /** Blurred snapshot of the screen behind a panel or menu that is opening. */
    var snapshot: Bitmap? = null

    /** Decoded background images (wallpaper / per-app), bounded by memory. */
    private val bgCache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            // Debug builds log any disk or network access on the main thread (used for profiling).
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork()
                    .detectCustomSlowCalls().penaltyLog().build()
            )
        }
        prefs = Prefs(this)
        repo = AppRepository(this, prefs)
        repo.refresh()
        registerPackageReceiver()
        repo.worker.execute {
            Fonts.preload(this)
            ImageFiles.warm(this)
        }
        updater = Updater(this)
        updater.startupCleanup()
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                repo.onPackageChanged(intent.data?.schemeSpecificPart)
            }
        }
        // System broadcasts are still delivered to a NOT_EXPORTED receiver.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    fun bgToken(file: File, blur: Int) = "${file.path}|${file.lastModified()}|$blur"

    /** Loads (and caches) a background image off the main thread; callback on the main thread. */
    fun loadBackground(file: File, blur: Int, callback: (token: String, bmp: Bitmap?) -> Unit) {
        val token = bgToken(file, blur)
        bgCache.get(token)?.let {
            callback(token, it)
            return
        }
        val screenW = resources.displayMetrics.widthPixels
        repo.worker.execute {
            val bmp = try {
                ImageOps.decodeBackground(file, blur, screenW)
            } catch (e: Throwable) {
                null
            }
            main.post {
                if (bmp != null) bgCache.put(token, bmp)
                callback(token, bmp)
            }
        }
    }

    fun clearBackgroundCache() = bgCache.evictAll()
}

val Context.launcher: LauncherApp get() = applicationContext as LauncherApp
