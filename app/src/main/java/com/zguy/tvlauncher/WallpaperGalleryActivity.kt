package com.zguy.tvlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileInputStream

/**
 * Wallpaper browser: thumbnails of the built-in Quiet Home Originals and of your imported
 * images and videos (from the import folder). Choosing an image opens the full-screen preview
 * before anything is applied; choosing a video offers wallpaper / screensaver / both / still frame.
 */
class WallpaperGalleryActivity : Activity() {

    private sealed class Entry {
        class Header(val title: String, val note: String) : Entry()
        class Original(val item: Originals.Item) : Entry()
        class Photo(val photo: Photos.Photo) : Entry()
        class Image(val file: File) : Entry()
        class Clip(val file: File) : Entry()
    }

    private lateinit var app: LauncherApp
    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var grid: RecyclerView
    private lateinit var menu: GlassMenu
    private val main = Handler(Looper.getMainLooper())
    private val entries = ArrayList<Entry>()
    private var key: String? = null
    private var label = ""
    private var tileW = 0
    private var tileH = 0
    private val videoInfo = HashMap<String, Video.Info>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        app = launcher
        prefs = app.prefs
        key = intent.getStringExtra(EXTRA_KEY)
        label = intent.getStringExtra(EXTRA_LABEL) ?: ""
        root = findViewById(R.id.root)
        grid = findViewById(R.id.grid)
        menu = GlassMenu(this, root, findViewById(R.id.content))

        val backdrop = findViewById<BackdropView>(R.id.backdrop)
        val s = BgStyle.from(prefs)
        backdrop.applyStyle(s)
        backdrop.showGlobal(app, s, 0)
        findViewById<GlassView>(R.id.header_glass).cornerRadius = Ui.dpf(this, 24f)
        findViewById<TextView>(R.id.title).text = if (key == null) "Wallpapers" else "Background for $label"

        val cols = 4
        val dm = resources.displayMetrics
        tileW = (dm.widthPixels - Ui.dp(this, 96)) / cols - Ui.dp(this, 20)
        tileH = tileW * 9 / 16
        val lm = GridLayoutManager(this, cols)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (entries[position] is Entry.Header) cols else 1
        }
        grid.layoutManager = lm
        grid.adapter = Adapter()
        // Only tiles take focus (headers are not focusable); never the grid itself.
        grid.isFocusable = false
        grid.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        buildEntries()
        Fonts.apply(root)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, if (prefs.motionLevel == Prefs.MOTION_OFF) 0 else android.R.anim.fade_out)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (menu.isShowing) menu.dismiss() else finish()
    }

    private fun importDir(): File? = getExternalFilesDir("wallpapers")

    private fun buildEntries() {
        entries.clear()
        for (cat in listOf("Nature", "Abstract")) {
            entries += Entry.Header(
                cat,
                if (cat == "Nature") "Mountains, lakes, forests, coastlines and dunes. Photos from Unsplash, free under the Unsplash License."
                else "Minimal shapes and soft glass-like gradients. Photos from Unsplash, free under the Unsplash License.",
            )
            Photos.ALL.filter { it.category == cat }.forEach { entries += Entry.Photo(it) }
        }
        entries += Entry.Header(
            "Quiet Home Originals",
            "Apple-inspired aerial, ocean, nature and abstract wallpapers, drawn by this app. Not Apple images.",
        )
        Originals.ALL.forEach { entries += Entry.Original(it) }
        val files = importDir()?.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
        val images = files.filter(ImageOps::isImage)
        val clips = if (key == null) files.filter(Video::isVideo) else emptyList()
        entries += Entry.Header(
            "Imported — your files",
            if (images.isEmpty() && clips.isEmpty()) "Nothing imported yet. Copy images or videos to ${importDir()?.absolutePath} (see README)."
            else "Images and videos you added. Use only files you have permission to use, including any Apple wallpapers or aerial videos.",
        )
        images.forEach { entries += Entry.Image(it) }
        clips.forEach { entries += Entry.Clip(it) }
        grid.adapter?.notifyDataSetChanged()
        grid.post { grid.getChildAt(1)?.requestFocus() }
    }

    // ---- Choosing ----

    private fun choose(e: Entry, anchor: View) {
        when (e) {
            is Entry.Original -> renderToPreview { out ->
                val bmp = Originals.render(e.item, 1920, 1080)
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                bmp.recycle()
                true
            }
            is Entry.Image -> renderToPreview { out -> ImageOps.importScaled({ FileInputStream(e.file) }, out, 1920, 1080) }
            is Entry.Photo -> renderToPreview { out ->
                assets.open(e.photo.asset).use { input -> out.outputStream().use { input.copyTo(it) } }
                true
            }
            is Entry.Clip -> videoMenu(e.file, anchor)
            is Entry.Header -> Unit
        }
    }

    private fun renderToPreview(make: (File) -> Boolean) {
        GlassMenu.chip(this, root, "Preparing preview…")
        app.repo.worker.execute {
            val tmp = ImageFiles.temp(this)
            val ok = runCatching { make(tmp) }.getOrDefault(false)
            main.post {
                if (isDestroyed) return@post
                if (ok) BackgroundEditorActivity.preview(this, tmp, key) else GlassMenu.chip(this, root, "Couldn't read that image")
            }
        }
    }

    private fun videoMenu(file: File, anchor: View) {
        val info = videoInfo[file.path]
        if (info != null && !info.supported) {
            menu.show(anchor, file.name, listOf(GlassMenu.Item("OK") {}), message = "${info.reason}. Try an H.264 or HEVC (SDR) version of this video.")
            return
        }
        val items = listOf(
            GlassMenu.Item("Preview full screen") { ScreensaverActivity.preview(this, file) },
            GlassMenu.Item("Set as wallpaper") { setVideoWallpaper(file) },
            GlassMenu.Item("Set as screensaver") { setScreensaver(file) },
            GlassMenu.Item("Use for both") {
                setVideoWallpaper(file)
                setScreensaver(file, quiet = true)
            },
            GlassMenu.Item("Use a still frame as wallpaper") {
                renderToPreview { out -> Video.ensureStill(this, file)?.copyTo(out, overwrite = true) != null }
            },
        )
        val dur = info?.durationMs?.let { if (it > 0) " · ${it / 1000 / 60}:${"%02d".format(it / 1000 % 60)}" else "" } ?: ""
        val res = info?.let { if (it.width > 0) "${it.width}×${it.height}" else null } ?: "Video"
        menu.show(anchor, file.name, items, message = "$res$dur · plays silently and loops")
    }

    private fun setVideoWallpaper(file: File) {
        prefs.videoPath = file.path
        prefs.bgType = Prefs.BG_VIDEO
        if (prefs.perAppMode == Prefs.PER_APP_CUSTOM_AND_ART) prefs.perAppMode = Prefs.PER_APP_CUSTOM
        GlassMenu.chip(this, root, "Video wallpaper set")
    }

    private fun setScreensaver(file: File, quiet: Boolean = false) {
        prefs.screensaverVideo = file.path
        prefs.screensaverSource = Prefs.SS_VIDEO
        if (prefs.screensaverIdleMin == 0) prefs.screensaverIdleMin = 10
        if (!quiet) GlassMenu.chip(this, root, "Screensaver set")
        else GlassMenu.chip(this, root, "Wallpaper and screensaver set")
    }

    // ---- Grid ----

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = entries.size
        override fun getItemViewType(position: Int) = if (entries[position] is Entry.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 0) HeaderHolder(parent) else TileHolder(parent)

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val e = entries[position]
            if (h is HeaderHolder && e is Entry.Header) h.bind(e)
            if (h is TileHolder) h.bind(e)
        }
    }

    private inner class HeaderHolder(parent: ViewGroup) : RecyclerView.ViewHolder(LinearLayout(parent.context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = RecyclerView.LayoutParams(-1, -2)
        setPadding(Ui.dp(context, 10), Ui.dp(context, 22), Ui.dp(context, 10), Ui.dp(context, 10))
    }) {
        private val title = TextView(parent.context).apply { textSize = 22f; tag = "display-semibold"; setTextColor(Color.WHITE) }
        private val note = TextView(parent.context).apply { textSize = 14f; setTextColor(0xB3FFFFFF.toInt()) }

        init {
            (itemView as LinearLayout).addView(title)
            (itemView as LinearLayout).addView(note)
        }

        fun bind(e: Entry.Header) {
            title.text = e.title
            note.text = e.note
            Fonts.apply(itemView)
        }
    }

    private inner class TileHolder(parent: ViewGroup) : RecyclerView.ViewHolder(LinearLayout(parent.context).apply {
        orientation = LinearLayout.VERTICAL
        isFocusable = true
        isClickable = true
        clipChildren = false
        layoutParams = RecyclerView.LayoutParams(-1, -2)
        setPadding(Ui.dp(context, 10), Ui.dp(context, 10), Ui.dp(context, 10), Ui.dp(context, 10))
    }) {
        private val frame = FrameLayout(parent.context)
        private val img = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val badge = TextView(parent.context).apply {
            textSize = 11f
            letterSpacing = 0.08f
            tag = "text-semibold"
            setTextColor(Color.WHITE)
            setPadding(Ui.dp(context, 8), Ui.dp(context, 3), Ui.dp(context, 8), Ui.dp(context, 3))
        }
        private val name = TextView(parent.context).apply {
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(0xE6FFFFFF.toInt())
            setPadding(0, Ui.dp(context, 8), 0, 0)
        }
        private var boundId = ""

        init {
            frame.addView(img, FrameLayout.LayoutParams(-1, -1))
            frame.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(Ui.dp(parent.context, 8), Ui.dp(parent.context, 8), 0, 0)
            })
            frame.clipToOutline = true
            frame.background = GradientDrawable().apply { cornerRadius = Ui.dpf(parent.context, 12f); setColor(0x33FFFFFF) }
            (itemView as LinearLayout).addView(frame, LinearLayout.LayoutParams(tileW, tileH))
            (itemView as LinearLayout).addView(name)
            itemView.setOnClickListener {
                val p = bindingAdapterPosition
                if (p >= 0) choose(entries[p], itemView)
            }
            itemView.setOnFocusChangeListener { v, f ->
                frame.foreground = if (f) Ui.ring(prefs.accentColor, Ui.dpf(v.context, 12f), Ui.dp(v.context, 3)) else null
                name.setTextColor(if (f) Color.WHITE else 0xE6FFFFFF.toInt())
                Ui.focusScale(v, f, Motion.of(prefs), 1.06f)
                v.translationZ = if (f) 2f else 0f
            }
            Fonts.apply(itemView)
        }

        fun bind(e: Entry) {
            img.setImageDrawable(null)
            val (id, title, tag) = when (e) {
                is Entry.Original -> Triple("o:${e.item.id}", "${e.item.name}  ·  ${e.item.category}", "ORIGINAL")
                is Entry.Photo -> Triple("p:${e.photo.id}", "${e.photo.title}  ·  ${e.photo.photographer}", "UNSPLASH")
                is Entry.Image -> Triple("i:${e.file.path}", e.file.name, "IMPORTED")
                is Entry.Clip -> Triple("v:${e.file.path}", e.file.name, "VIDEO")
                else -> Triple("", "", "")
            }
            boundId = id
            name.text = title
            badge.text = tag
            badge.background = GradientDrawable().apply {
                cornerRadius = Ui.dpf(itemView.context, 10f)
                setColor(if (e is Entry.Original) 0x99335BFF.toInt() else 0x99000000.toInt())
            }
            thumbs.get(id)?.let {
                img.setImageBitmap(it)
                videoInfo[(e as? Entry.Clip)?.file?.path ?: ""]?.let { info -> markUnsupported(info) }
                return
            }
            app.repo.worker.execute {
                var info: Video.Info? = null
                val bmp: Bitmap? = runCatching {
                    when (e) {
                        is Entry.Original -> Originals.render(e.item, tileW, tileH)
                        is Entry.Photo -> assets.open(e.photo.asset).use { input ->
                            val o = BitmapFactory.Options().apply { inSampleSize = 4 }
                            BitmapFactory.decodeStream(input, null, o)?.let { src -> ImageOps.cover(src, tileW, tileH).also { src.recycle() } }
                        }
                        is Entry.Image -> {
                            val o = BitmapFactory.Options().apply { inSampleSize = 8 }
                            BitmapFactory.decodeFile(e.file.path, o)?.let { src -> ImageOps.cover(src, tileW, tileH).also { src.recycle() } }
                        }
                        is Entry.Clip -> {
                            info = Video.probe(e.file)
                            Video.frame(e.file, tileW)?.let { src -> ImageOps.cover(src, tileW, tileH).also { src.recycle() } }
                        }
                        else -> null
                    }
                }.getOrNull()
                main.post {
                    info?.let { videoInfo[it.file.path] = it }
                    if (bmp != null) thumbs.put(id, bmp)
                    if (boundId == id) {
                        img.setImageBitmap(bmp)
                        info?.let { markUnsupported(it) }
                    }
                }
            }
        }

        private fun markUnsupported(info: Video.Info) {
            if (!info.supported) badge.text = "VIDEO · NOT SUPPORTED"
        }
    }

    companion object {
        private const val EXTRA_KEY = "key"
        private const val EXTRA_LABEL = "label"
        private val thumbs = LruCache<String, Bitmap>(48)

        /** [entry] null = global wallpaper; otherwise a per-app background (images only). */
        fun open(from: Activity, key: String?, label: String?) {
            GlassView.captureSnapshot(from, translucent = from is SettingsActivity)
            from.startActivity(
                Intent(from, WallpaperGalleryActivity::class.java)
                    .putExtra(EXTRA_KEY, key)
                    .putExtra(EXTRA_LABEL, label)
            )
        }
    }
}
