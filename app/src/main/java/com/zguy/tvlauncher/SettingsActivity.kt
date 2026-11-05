package com.zguy.tvlauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout

/**
 * Settings panel. Slides in from the right over a lightly dimmed screen; Home stays visible
 * on the left and restyles live as values change. Every change is saved immediately.
 */
class SettingsActivity : Activity() {

    private lateinit var app: LauncherApp
    private lateinit var prefs: Prefs
    private lateinit var dim: View
    private lateinit var panel: GlassView
    private lateinit var rows: RowList
    private lateinit var menu: GlassMenu
    private lateinit var root: FrameLayout
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        app = launcher
        prefs = app.prefs
        dim = findViewById(R.id.dim)
        panel = findViewById(R.id.panel)
        root = findViewById(R.id.root)
        panel.cornerRadius = Ui.dpf(this, 28f)
        panel.opacityBoost = 0.3f
        panel.snapshot = app.snapshot
        menu = GlassMenu(this, root, findViewById(R.id.panel_content))
        rows = RowList(this, findViewById(R.id.list))
        rows.onAnyChange = {
            // Once something changes, sample the live background so the glass matches the preview behind.
            panel.snapshot = null
            stylePanel()
            preview?.applyStyle()
            Fonts.apply(panel)
        }

        buildRows()
        stylePanel()
        Fonts.apply(root)
        rows.firstVisible()?.requestFocus()
        animateIn()
    }

    override fun onResume() {
        super.onResume()
        rows.refreshAll()
        preview?.start()
    }

    override fun onPause() {
        preview?.stop()
        super.onPause()
    }

    // ---- Panel motion ----

    private fun animateIn() {
        val m = Motion.of(prefs)
        val d = m.d(320)
        if (d <= 0) {
            dim.alpha = DIM
            return
        }
        dim.animate().alpha(DIM).setDuration(d).start()
        panel.alpha = 0f
        val a = panel.animate().alpha(1f).setDuration(d).setInterpolator(Motion.EASE_OUT)
        if (m.full) {
            // Soft slide + fade: the floating panel glides in a short distance rather than the full width.
            panel.translationX = Ui.dpf(this, 56f)
            a.translationX(0f)
        }
        a.withEndAction { panel.invalidate() }.start()
    }

    override fun finish() {
        if (closing) return
        closing = true
        val m = Motion.of(prefs)
        val d = m.d(240)
        val done = {
            super.finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        if (d <= 0) {
            done()
            return
        }
        dim.animate().alpha(0f).setDuration(d).start()
        val a = panel.animate().alpha(0f).setDuration(d).setInterpolator(Motion.EASE_IN)
        if (m.full) a.translationX(Ui.dpf(this, 56f))
        a.withEndAction(done).start()
    }

    private fun stylePanel() {
        panel.refreshStyle()
        app.glassHub.restyle()
        rows.restyle(prefs.accentColor)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (menu.isShowing) menu.dismiss() else finish()
    }

    // ---- Rows ----

    private fun buildRows() = with(rows) {
        val isImage = { prefs.bgType == Prefs.BG_IMAGE }
        val isVideo = { prefs.bgType == Prefs.BG_VIDEO }
        val isGradient = { prefs.bgType == Prefs.BG_GRADIENT }
        val hasWallpaper = { ImageFiles.wallpaper(this@SettingsActivity).exists() }
        val videoName = { java.io.File(prefs.videoPath).let { if (it.exists()) it.name else "None — choose in gallery" } }

        header("Appearance › Wallpaper")
        add(ActionRow("Browse wallpapers…", { "Originals & imported" }) { WallpaperGalleryActivity.open(this@SettingsActivity, null, null) })
        add(ChoiceRow("Type", Prefs.BG_TYPES, true, { prefs.bgType }, { prefs.bgType = it }))
        add(ChoiceRow("Colour", Prefs.COLOURS, true, { prefs.bgSolid }, { prefs.bgSolid = it }, { it }),
            visibleWhen = { prefs.bgType == Prefs.BG_SOLID || (isImage() && !hasWallpaper()) })
        add(ChoiceRow("Gradient top", Prefs.COLOURS, true, { prefs.gradTop }, { prefs.gradTop = it }, { it }), visibleWhen = isGradient)
        add(ChoiceRow("Gradient bottom", Prefs.COLOURS, true, { prefs.gradBottom }, { prefs.gradBottom = it }, { it }), visibleWhen = isGradient)
        add(ChoiceRow("Gradient direction", Prefs.GRAD_DIRS, true, { prefs.gradDir }, { prefs.gradDir = it }), visibleWhen = isGradient)
        add(ActionRow("Wallpaper image", { if (hasWallpaper()) "Change…" else "Choose…" }) {
            PickImageActivity.start(this@SettingsActivity, PickImageActivity.TARGET_WALLPAPER, null)
        }, visibleWhen = isImage)
        add(ActionRow("Adjust & preview wallpaper") {
            BackgroundEditorActivity.edit(this@SettingsActivity, ImageFiles.wallpaper(this@SettingsActivity), null)
        }, visibleWhen = { isImage() && hasWallpaper() })
        add(ActionRow("Video", videoName) { WallpaperGalleryActivity.open(this@SettingsActivity, null, null) }, visibleWhen = isVideo)
        add(ToggleRow("Animated (off = still frame)", { prefs.animatedWallpaper }, { prefs.animatedWallpaper = it }), visibleWhen = isVideo)
        add(ChoiceRow("Position", Prefs.POSITIONS, true, { prefs.wallpaperPosition }, { prefs.wallpaperPosition = it }),
            visibleWhen = { (isImage() && hasWallpaper()) || isVideo() })
        add(ChoiceRow("Change with selected app", Prefs.PER_APP, true, { prefs.perAppMode }, { prefs.perAppMode = it }))
        add(ActionRow("Wallpaper credits", { "Unsplash photographers" }) {
            menu.show(currentFocus, "Wallpaper credits", listOf(GlassMenu.Item("Close") {}), message = Photos.credits())
        })

        header("Appearance › Wallpaper look")
        add(ChoiceRow("Brightness", Prefs.BRIGHTNESS, false, { prefs.brightness }, { prefs.brightness = it }))
        add(ChoiceRow("Dimming", Prefs.DIM, false, { prefs.dimPct }, { prefs.dimPct = it }))
        add(ChoiceRow("Blur (images & stills)", Prefs.BLUR, false, { prefs.blur }, { prefs.blur = it }))
        add(ChoiceRow("Overlay colour", Prefs.OVERLAY_COLOURS, true, { prefs.overlayColor }, { prefs.overlayColor = it }, { it }))
        add(ChoiceRow("Overlay opacity", Prefs.OPACITY, false, { prefs.overlayOpacity }, { prefs.overlayOpacity = it }))
        add(ActionRow("Restore default wallpaper settings") {
            confirm("Restore default wallpaper?", "Type, colours, position and look settings go back to defaults. Your images and videos are kept.") {
                prefs.resetBackground()
                app.clearBackgroundCache()
                toast("Wallpaper settings restored")
            }
        })
        add(ActionRow("Remove all per-app backgrounds") {
            confirm("Remove all per-app backgrounds?", "Apps will use their artwork or the main wallpaper again.") {
                ImageFiles.clearDir(this@SettingsActivity, "bg")
                app.clearBackgroundCache()
                prefs.touch()
                toast("Per-app backgrounds removed")
            }
        })

        header("Appearance › Clock & Greeting")
        addCustom(clockPreview())
        add(ActionRow("Display name", { prefs.displayName.ifBlank { "(none)" } }) { editName() })
        add(ToggleRow("Show clock", { prefs.showClock }, { prefs.showClock = it }))
        add(ToggleRow("Show greeting", { prefs.showGreeting }, { prefs.showGreeting = it }))
        add(ToggleRow("Name in greeting", { prefs.greetingName }, { prefs.greetingName = it }), visibleWhen = { prefs.showGreeting })
        add(ChoiceRow("Clock format", Prefs.CLOCK_FORMATS, true, { prefs.clockFormat }, { prefs.clockFormat = it }))
        add(ToggleRow("Glass backing", { prefs.clockGlass }, { prefs.clockGlass = it }))

        header("Appearance › Typography")
        add(ActionRow("Font", { Fonts.status(this@SettingsActivity) }) { fontInfo() })
        add(ToggleRow("Apple-style fonts (off = Roboto)", { prefs.useCustomFonts }, { prefs.useCustomFonts = it }))
        add(ChoiceRow("Text size", Prefs.TEXT_SCALES, false, { prefs.textScalePct }, { prefs.textScalePct = it }))

        header("Appearance › Glass")
        add(ChoiceRow("Visual effects", Prefs.EFFECTS, false, { prefs.effects }, { prefs.effects = it }))
        add(ChoiceRow("Glass blur", Prefs.GLASS_BLUR, false, { prefs.glassBlur }, { prefs.glassBlur = it }),
            visibleWhen = { !prefs.lightweight })
        add(ChoiceRow("Glass opacity", Prefs.OPACITY, false, { prefs.glassOpacity }, { prefs.glassOpacity = it }))
        add(ChoiceRow("Glass tint", Prefs.GLASS_TINTS, true, { prefs.glassTint }, { prefs.glassTint = it }, { it }))

        header("Appearance › Cards")
        add(ChoiceRow("Card size", Prefs.CARD_SIZES, false, { prefs.cardWidthDp }, { prefs.cardWidthDp = it },
            note = { app.maxFitCardDp.takeIf { it in 1 until prefs.cardWidthDp }?.let { "shown at $it (max for this screen)" } }))
        add(ChoiceRow("Spacing", Prefs.SPACINGS, false, { prefs.spacingDp }, { prefs.spacingDp = it }))
        add(ChoiceRow("Corner radius", Prefs.CORNERS, false, { prefs.cornerDp }, { prefs.cornerDp = it }))
        add(ChoiceRow("Accent (focus glow)", Prefs.ACCENTS, true, { prefs.accentColor }, { prefs.accentColor = it }, { it }))
        add(ToggleRow("App names under cards", { prefs.showLabels }, { prefs.showLabels = it }))
        add(ToggleRow("Use TV banner artwork", { prefs.useBanners }, {
            prefs.useBanners = it
            app.repo.invalidateAllArtwork()
        }))
        add(ActionRow("Remove all custom artwork") {
            confirm("Remove all custom artwork?", "Cards go back to each app's own artwork.") {
                ImageFiles.clearDir(this@SettingsActivity, "art")
                app.repo.invalidateAllArtwork()
                prefs.touch()
                toast("Custom artwork removed")
            }
        })

        header("Screensaver")
        add(ChoiceRow("Start after", Prefs.IDLE_TIMES, false, { prefs.screensaverIdleMin }, { prefs.screensaverIdleMin = it }))
        add(ChoiceRow("Show", Prefs.SS_SOURCES, true, { prefs.screensaverSource }, { prefs.screensaverSource = it }))
        add(ActionRow("Screensaver video", { java.io.File(prefs.screensaverVideo).let { if (it.exists()) it.name else "None — choose in gallery" } }) {
            WallpaperGalleryActivity.open(this@SettingsActivity, null, null)
        }, visibleWhen = { prefs.screensaverSource == Prefs.SS_VIDEO })
        add(ToggleRow("Clock", { prefs.screensaverClock }, { prefs.screensaverClock = it }))
        add(ToggleRow("Greeting", { prefs.screensaverGreeting }, { prefs.screensaverGreeting = it }))
        add(ActionRow("Preview screensaver") { ScreensaverActivity.preview(this@SettingsActivity) })
        add(ActionRow("System screensaver (Google TV)") { systemScreensaverInfo() })

        header("Home screen")
        add(ChoiceRow("Screen margins (overscan)", Prefs.SAFE_MARGINS, false, { prefs.safeMarginPct }, { prefs.safeMarginPct = it }))
        add(ToggleRow("Network status", { prefs.showNetwork }, { prefs.showNetwork = it }))
        add(ToggleRow("App description", { prefs.showDescription }, { prefs.showDescription = it }))
        add(ToggleRow("Remote control hints", { prefs.showHints }, { prefs.showHints = it }))

        header("Motion")
        add(ChoiceRow("Animations", Prefs.MOTION, false, { prefs.motionLevel }, { prefs.motionLevel = it }))
        add(ChoiceRow("Animation speed", Prefs.SPEEDS, false, { prefs.animSpeedPct }, { prefs.animSpeedPct = it }),
            visibleWhen = { prefs.motionLevel != Prefs.MOTION_OFF })

        header("All apps")
        add(ChoiceRow("Columns", Prefs.COLUMN_CHOICES, false, { prefs.columns }, { prefs.columns = it }))
        add(ToggleRow("Include non-TV apps", { prefs.includeNonTvApps }) {
            prefs.includeNonTvApps = it
            app.repo.refresh()
        })
        add(ActionRow("Reset pinned apps") {
            confirm("Reset pinned apps?", "Home will go back to the default set of apps.") {
                prefs.resetPinned()
                app.repo.refresh()
                toast("Pinned apps reset")
            }
        })

        header("System")
        add(ActionRow("Home button", { if (Ui.isDefaultHome(this@SettingsActivity)) "Opens Quiet Home" else "Opens Google TV" }) {
            showHomeInfo()
        })
        add(ActionRow("Android TV settings") {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e: Exception) {
                toast("Not available")
            }
        })
        add(ActionRow("Reset everything to defaults") {
            confirm("Reset all settings?", "Every setting returns to its default. Pinned apps, custom artwork, images and fonts are kept.") {
                prefs.resetAll()
                app.clearBackgroundCache()
                app.repo.invalidateAllArtwork()
                app.repo.refresh()
                toast("Settings reset")
            }
        })

        header("About")
        add(ActionRow("Updates", { updateSummary() }) {
            startActivity(Intent(this@SettingsActivity, UpdatesActivity::class.java))
        })
        add(ActionRow("Version", { versionName() }) { })
        refreshAll()
    }

    // ---- Clock & greeting preview, name, fonts, screensaver info ----

    private var preview: ClockGreeting? = null

    private fun clockPreview(): View {
        val v = layoutInflater.inflate(R.layout.view_clock_preview, null)
        val glass = v.findViewById<GlassView>(R.id.preview_glass)
        glass.cornerRadius = Ui.dpf(this, 18f)
        preview = ClockGreeting(this, v.findViewById(R.id.preview_clock), v.findViewById(R.id.preview_greeting))
        preview?.applyStyle()
        return v
    }

    private fun editName() {
        val field = android.widget.EditText(this).apply {
            setText(prefs.displayName)
            setSelection(text.length)
            setSingleLine()
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(0x80FFFFFF.toInt())
            hint = "Your name"
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            background = Ui.focusBackground(this@SettingsActivity, prefs.accentColor, 12, 0.10f)
            setPadding(Ui.dp(context, 14), Ui.dp(context, 10), Ui.dp(context, 14), Ui.dp(context, 10))
        }
        val save = {
            prefs.displayName = field.text.toString()
            rows.refreshAll()
            preview?.applyStyle()
            toast(if (prefs.displayName.isBlank()) "Greeting will show without a name" else "Hello, ${prefs.displayName}")
        }
        field.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                menu.dismiss()
                save()
                true
            } else false
        }
        menu.show(
            anchor = currentFocus, title = "Display name",
            message = "Shown in the greeting below the clock. Leave blank for no name.",
            field = field,
            items = listOf(GlassMenu.Item("Save") { save() }, GlassMenu.Item("Cancel") {}),
        )
    }

    private fun fontInfo() {
        val dir = Fonts.importDir(this)?.absolutePath
        menu.show(
            currentFocus, "Typography",
            message = "Order: SF Pro › Helvetica › TeX Gyre Heros (built in) › Roboto.\n\n" +
                "SF Pro and Helvetica are commercial fonts and are not included; SF Pro's licence only covers mock-ups " +
                "for Apple platforms. If you have files you are licensed to use, copy them to:\n$dir\n" +
                "SF Pro names need Display or Text plus Regular/Medium/Semibold (SF-Pro-Display-Semibold.otf). " +
                "Helvetica names need Roman/Regular, Medium or Bold (HelveticaNeue-Medium.ttf).\n\n" +
                "TeX Gyre Heros is a free Helvetica-style font (GUST Font License).\n\nNow: ${Fonts.status(this)}",
            items = listOf(
                GlassMenu.Item("Import fonts from folder") {
                    val n = Fonts.importFromFolder(this)
                    prefs.touch()
                    rows.refreshAll()
                    toast(if (n == 0) "No matching font files found" else "Imported $n font files")
                },
                GlassMenu.Item("Remove imported fonts") {
                    Fonts.removeCustom(this)
                    prefs.touch()
                    rows.refreshAll()
                    toast("Using ${Fonts.status(this)}")
                },
                GlassMenu.Item("Close") {},
            ),
        )
    }

    private fun systemScreensaverInfo() {
        menu.show(
            currentFocus, "System screensaver",
            message = "Google TV normally only offers its own Ambient Mode, so Quiet Home's screensaver runs inside the " +
                "launcher (it starts after the idle time while Quiet Home is on screen).\n\nQuiet Home also includes a " +
                "system screensaver. On some firmware it can be selected over ADB — see the README. Google TV may ignore it.",
            items = listOf(GlassMenu.Item("Close") {}),
        )
    }

    private fun updateSummary(): String = when (val st = app.updater.state) {
        Updater.State.NotConfigured -> "Not configured"
        is Updater.State.Available -> "Update available: ${st.info.versionName}"
        is Updater.State.Ready, is Updater.State.NeedsPermission -> "Ready to install"
        is Updater.State.Downloading -> "Downloading…"
        is Updater.State.UpToDate -> "Up to date"
        else -> "Check for updates"
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        menu.show(
            anchor = currentFocus, title = title, message = message, focusIndex = 1,
            items = listOf(
                GlassMenu.Item("Reset") {
                    onYes()
                    rows.refreshAll()
                    stylePanel()
                },
                GlassMenu.Item("Cancel") {},
            ),
        )
    }

    private fun showHomeInfo() {
        val isHome = Ui.isDefaultHome(this)
        val homeSettings = Intent(Settings.ACTION_HOME_SETTINGS)
        val canChoose = homeSettings.resolveActivity(packageManager) != null
        val msg = if (isHome) {
            "Quiet Home is the default Home app on this TV."
        } else {
            "The Home button currently opens the Google TV home screen. Quiet Home still works from the Apps row as a normal app.\n\n" +
                if (canChoose) "This TV offers a Home app chooser — select Quiet Home there."
                else "This TV's firmware has no Home app chooser. See the README for optional ADB setup and how to undo it."
        }
        val items = ArrayList<GlassMenu.Item>()
        if (canChoose && !isHome) items += GlassMenu.Item("Choose Home app") {
            try {
                startActivity(homeSettings)
            } catch (e: Exception) {
                toast("Not available")
            }
        }
        items += GlassMenu.Item("Close") {}
        menu.show(currentFocus, "Home button", items, message = msg)
    }

    private fun toast(msg: String) = GlassMenu.chip(this, root, msg)

    companion object {
        private const val DIM = 0.38f
    }
}
