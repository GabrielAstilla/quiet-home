package com.zguy.tvlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File

/**
 * Preview before saving: shows a wallpaper / per-app background exactly as Home will draw it
 * (with sample title and cards for readability), and lets you adjust position, blur,
 * brightness, dimming and overlay with the remote. Back or Cancel discards everything.
 */
class BackgroundEditorActivity : Activity() {

    private lateinit var app: LauncherApp
    private lateinit var prefs: Prefs
    private lateinit var backdrop: BackdropView
    private lateinit var file: File
    private var key: String? = null
    private var isTemp = false
    private var saved = false

    // Draft values: applied to the preview only until Save.
    private var position = Prefs.POS_CROP
    private var blur = 0
    private var brightness = 100
    private var dim = 0
    private var overlayOpacity = 40

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bg_editor)
        app = launcher
        prefs = app.prefs
        backdrop = findViewById(R.id.backdrop)
        file = File(intent.getStringExtra(EXTRA_FILE) ?: run { finish(); return })
        key = intent.getStringExtra(EXTRA_KEY)
        isTemp = intent.getBooleanExtra(EXTRA_TEMP, false)

        position = key?.let { prefs.appBgPosition(it) } ?: prefs.wallpaperPosition
        blur = prefs.blur
        brightness = prefs.brightness
        dim = prefs.dimPct
        overlayOpacity = prefs.overlayOpacity

        buildSampleCards()
        val controls = findViewById<LinearLayout>(R.id.controls)
        findViewById<GlassView>(R.id.controls_glass).apply {
            cornerRadius = Ui.dpf(this@BackgroundEditorActivity, 24f)
            opacityBoost = 0.3f
        }
        backdrop.publishesGlass = false
        val rows = RowList(this, controls)
        rows.onAnyChange = {
            render(fade = false)
            Fonts.apply(controls)
        }
        with(rows) {
            header(if (key == null) "Wallpaper" else "App background")
            add(ChoiceRow("Position", Prefs.POSITIONS, true, { position }, { position = it }))
            add(ChoiceRow("Blur", Prefs.BLUR, false, { blur }, { blur = it }))
            add(ChoiceRow("Brightness", Prefs.BRIGHTNESS, false, { brightness }, { brightness = it }))
            add(ChoiceRow("Dimming", Prefs.DIM, false, { dim }, { dim = it }))
            add(ChoiceRow("Overlay", Prefs.OPACITY, false, { overlayOpacity }, { overlayOpacity = it }))
            add(ActionRow("Save") { save() })
            add(ActionRow("Cancel") { finish() })
            restyle(prefs.accentColor)
        }
        rows.firstVisible()?.requestFocus()
        Fonts.apply(window.decorView)
        render(fade = true)
    }

    private fun draft() = BgStyle.from(prefs).copy(
        type = Prefs.BG_IMAGE, brightness = brightness, dim = dim, blur = blur, overlayOpacity = overlayOpacity,
    )

    private fun render(fade: Boolean) {
        val s = draft()
        backdrop.applyStyle(s)
        val seq = backdrop.beginRequest()
        val d = if (fade) Motion.of(prefs).d(260) else 0L
        app.loadBackground(file, blur) { t, bmp ->
            if (backdrop.isCurrent(seq)) backdrop.show(t, bmp, BgStyle.scaleType(position), d)
        }
    }

    private fun buildSampleCards() {
        val container = findViewById<LinearLayout>(R.id.sample_cards)
        val w = Ui.dp(this, 150)
        val h = w * 9 / 16
        val cardColor = Ui.blend(prefs.overlayColor, 0xFFFFFFFF.toInt(), 0.18f)
        repeat(3) { i ->
            val v = View(this)
            val lp = LinearLayout.LayoutParams(w, h).apply { marginEnd = Ui.dp(this@BackgroundEditorActivity, 18) }
            v.background = GradientDrawable().apply {
                cornerRadius = Ui.dpf(this@BackgroundEditorActivity, prefs.cornerDp.toFloat())
                setColor(cardColor)
                if (i == 1) setStroke(Ui.dp(this@BackgroundEditorActivity, 3), prefs.accentColor)
            }
            if (i == 1) {
                v.scaleX = 1.12f
                v.scaleY = 1.12f
            }
            container.addView(v, lp)
        }
    }

    private fun save() {
        val dest = key?.let { ImageFiles.appBackground(this, it) } ?: ImageFiles.wallpaper(this)
        if (isTemp) {
            dest.parentFile?.mkdirs()
            dest.delete()
            val ok = file.renameTo(dest) || runCatching { file.copyTo(dest, overwrite = true); file.delete() }.isSuccess
            if (!ok) {
                Toast.makeText(this, "Couldn't save the image", Toast.LENGTH_LONG).show()
                return
            }
        }
        val k = key
        var note = "Background saved"
        if (k == null) {
            prefs.bgType = Prefs.BG_IMAGE
            prefs.wallpaperPosition = position
            // App artwork would otherwise cover the new wallpaper on Home.
            if (prefs.perAppMode == Prefs.PER_APP_CUSTOM_AND_ART) {
                prefs.perAppMode = Prefs.PER_APP_CUSTOM
                note = "Wallpaper saved. App-artwork backgrounds turned off (Settings › Change with selected app)."
            }
        } else {
            prefs.setAppBgPosition(k, position)
            if (prefs.perAppMode == Prefs.PER_APP_OFF) prefs.perAppMode = Prefs.PER_APP_CUSTOM
        }
        prefs.blur = blur
        prefs.brightness = brightness
        prefs.dimPct = dim
        prefs.overlayOpacity = overlayOpacity
        app.clearBackgroundCache()
        prefs.touch()
        saved = true
        Toast.makeText(this, note, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun finish() {
        if (!saved && isTemp) file.delete()
        super.finish()
        @Suppress("DEPRECATION")
        if (prefs.motionLevel == Prefs.MOTION_OFF) overridePendingTransition(0, 0)
        else overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    companion object {
        private const val EXTRA_FILE = "file"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_TEMP = "temp"

        /** Preview a newly imported image; [key] null means the global wallpaper. */
        fun preview(from: Activity, temp: File, key: String?) = launch(from, temp, key, true)

        /** Adjust an image that is already saved. */
        fun edit(from: Activity, file: File, key: String?) = launch(from, file, key, false)

        private fun launch(from: Activity, file: File, key: String?, temp: Boolean) {
            from.startActivity(
                Intent(from, BackgroundEditorActivity::class.java)
                    .putExtra(EXTRA_FILE, file.path)
                    .putExtra(EXTRA_KEY, key)
                    .putExtra(EXTRA_TEMP, temp)
            )
        }
    }
}
