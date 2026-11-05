package io.github.gabrielastilla.quiethome

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Settings: a centred grid of section cards over a lightly dimmed Home. OK opens a section in a centred
 * glass panel (some sections have short sub-pages); Back returns one level and restores focus to the card
 * or row you came from. Every change is saved immediately and Home restyles live behind the panel.
 */
class SettingsActivity : Activity() {

    private lateinit var app: LauncherApp
    private lateinit var prefs: Prefs
    private lateinit var dim: View
    private lateinit var stage: FrameLayout
    private lateinit var overview: ScrollView
    private lateinit var grid: LinearLayout
    private lateinit var panel: GlassView
    private lateinit var panelContent: LinearLayout
    private lateinit var crumb: TextView
    private lateinit var sectionTitle: TextView
    private lateinit var sectionDesc: TextView
    private lateinit var sectionScroll: MaxHeightScrollView
    private lateinit var list: LinearLayout
    private lateinit var hints: TextView
    private lateinit var rows: RowList
    private lateinit var menu: GlassMenu
    private lateinit var root: FrameLayout
    private var closing = false

    // ---- Sections ----

    private enum class Page(val title: String, val desc: String, val icon: Int, val parent: Page? = null) {
        APPEARANCE("Appearance", "Accent colour, glass and typography", R.drawable.ic_set_appearance),
        CARDS("App Cards & Layout", "Card size, spacing, labels and margins", R.drawable.ic_set_cards),
        WALLPAPER("Wallpaper & Screensaver", "Wallpapers, imports, backgrounds, idle", R.drawable.ic_set_wallpaper),
        CLOCK("Clock & Greeting", "Clock format, name and greeting", R.drawable.ic_set_clock),
        MOTION("Motion & Performance", "Animation speed, reduced motion, effects", R.drawable.ic_set_motion),
        APPS("Apps", "Pinned apps, ordering and visibility", R.drawable.ic_set_apps),
        SETUP("Launcher Setup", "Home button status, backup and reset", R.drawable.ic_set_home),
        ABOUT("About & Updates", "Version, updates, credits, install", R.drawable.ic_set_about),

        // Sub-pages (opened from a row inside a section).
        TYPOGRAPHY("Typography", "Font and text size", R.drawable.ic_set_appearance, APPEARANCE),
        LOOK("Wallpaper Look", "Brightness, dimming, blur and overlay", R.drawable.ic_set_wallpaper, WALLPAPER),
        SCREENSAVER("Screensaver", "Idle time, content and overlay", R.drawable.ic_set_wallpaper, WALLPAPER),
    }

    private val topLevel = Page.values().filter { it.parent == null }
    private val stack = ArrayList<Page>()
    private val cards = HashMap<Page, View>()
    /** Focused row index per page, so Back lands where you left. */
    private val lastRow = HashMap<Page, Int>()
    private var lastCard: Page = Page.APPEARANCE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        app = launcher
        prefs = app.prefs
        root = findViewById(R.id.root)
        dim = findViewById(R.id.dim)
        stage = findViewById(R.id.stage)
        overview = findViewById(R.id.overview)
        grid = findViewById(R.id.grid)
        panel = findViewById(R.id.panel)
        panelContent = findViewById(R.id.panel_content)
        crumb = findViewById(R.id.crumb)
        sectionTitle = findViewById(R.id.section_title)
        sectionDesc = findViewById(R.id.section_desc)
        sectionScroll = findViewById(R.id.section_scroll)
        list = findViewById(R.id.list)
        hints = findViewById(R.id.settings_hints)
        panel.cornerRadius = Ui.dpf(this, 30f)
        panel.opacityBoost = 0.3f
        panel.followSnapshot = true
        menu = GlassMenu(this, root, stage)
        rows = newRowList()

        applySafeArea()
        buildOverview()
        Fonts.apply(root)
        showHints()
        cards[lastCard]?.requestFocus()
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

    /** Equal outer margins inside the TV-safe area, with the guide's own bottom area reserved. */
    private fun applySafeArea() {
        val dm = resources.displayMetrics
        val pct = prefs.safeMarginPct / 100f
        val mx = (dm.widthPixels * pct).toInt() + Ui.dp(this, 48)
        val my = (dm.heightPixels * pct).toInt()
        val guide = Ui.dp(this, 27) + my
        (hints.layoutParams as FrameLayout.LayoutParams).bottomMargin = guide
        stage.setPadding(mx, my + Ui.dp(this, 40), mx, guide + Ui.dp(this, 34))
        // The section list may be at most the stage height minus the panel's own header.
        stage.post {
            val avail = stage.height - stage.paddingTop - stage.paddingBottom
            val header = panelContent.paddingTop + panelContent.paddingBottom + crumb.height + sectionTitle.height +
                sectionDesc.height + Ui.dp(this, 22)
            sectionScroll.maxHeight = (avail - header).coerceAtLeast(Ui.dp(this, 160))
        }
    }

    private fun showHints() {
        hints.text = if (stack.isEmpty()) "← → ↑ ↓  choose   ·   OK  open   ·   BACK  close"
        else "↑ ↓  choose   ·   ← →  change   ·   OK  select   ·   BACK  back"
    }

    // ---- Overview grid ----

    private fun buildOverview() {
        grid.removeAllViews()
        cards.clear()
        val perRow = 4
        val gap = Ui.dp(this, 16)
        topLevel.chunked(perRow).forEachIndexed { r, rowPages ->
            // Each row is centred on its own, so an incomplete last row sits in the middle.
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                clipChildren = false
                clipToPadding = false
            }
            rowPages.forEachIndexed { i, page ->
                val card = makeCard(page)
                rowView.addView(card, LinearLayout.LayoutParams(Ui.dp(this, 184), LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    if (i > 0) marginStart = gap
                })
                cards[page] = card
            }
            grid.addView(rowView, LinearLayout.LayoutParams(-2, -2).apply { if (r > 0) topMargin = gap })
        }
    }

    private fun makeCard(page: Page): View {
        val ctx = this
        val card = GlassView(ctx).apply {
            cornerRadius = Ui.dpf(ctx, 22f)
            isFocusable = true
            isClickable = true
            clipChildren = false
            minimumHeight = Ui.dp(ctx, 128)
            setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 12))
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(ImageView(ctx).apply {
            setImageResource(page.icon)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(Ui.dp(ctx, 26), Ui.dp(ctx, 26)))
        column.addView(TextView(ctx).apply {
            text = page.title
            tag = "text-semibold"
            textSize = 15f
            gravity = Gravity.CENTER
            maxLines = 2
            setTextColor(Color.WHITE)
            setPadding(0, Ui.dp(ctx, 8), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        column.addView(TextView(ctx).apply {
            text = page.desc
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(0xB3FFFFFF.toInt())
            setPadding(0, Ui.dp(ctx, 3), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        card.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        card.foreground = cardRing().apply { alpha = 0 }
        card.setOnClickListener { open(page) }
        card.setOnFocusChangeListener { v, f ->
            if (f) lastCard = page
            focusCard(v, f)
        }
        return card
    }

    private fun cardRing() = Ui.ring(prefs.accentColor, Ui.dpf(this, 22f), Ui.dp(this, 3)).apply {
        setColor(Ui.withAlpha(Color.WHITE, 0.10f))
    }

    /** Clear border + subtle lift and scale; animated from the current state, so rapid moves never queue. */
    private fun focusCard(v: View, on: Boolean) {
        // Read the ring from the view each time: it is replaced when the accent colour changes.
        val ring = v.foreground ?: return
        val m = Motion.of(prefs)
        val target = if (on) 255 else 0
        v.animate().cancel()
        val lift = if (on && m.full) -Ui.dpf(this, 6f) else 0f
        val scale = if (on && m.enabled) 1.04f else 1f
        v.translationZ = if (on) 2f else 0f
        if (!m.enabled) {
            ring.alpha = target
            v.translationY = lift; v.scaleX = scale; v.scaleY = scale
            return
        }
        val from = ring.alpha
        v.animate().translationY(lift).scaleX(scale).scaleY(scale).setDuration(m.d(180)).setInterpolator(Motion.EASE_OUT)
            .setUpdateListener { ring.alpha = (from + (target - from) * it.animatedFraction).toInt() }
            .start()
    }

    // ---- Opening and closing sections ----

    private fun open(page: Page) {
        val fromOverview = stack.isEmpty()
        stack.lastOrNull()?.let { cur -> lastRow[cur] = focusedRowIndex() }
        stack += page
        showPage(page, forward = true, fromOverview = fromOverview)
    }

    /** Back: one level up; from a top-level section, back to the overview with the same card focused. */
    private fun back() {
        if (stack.isEmpty()) return finish()
        val leaving = stack.removeAt(stack.size - 1)
        lastRow[leaving] = focusedRowIndex()
        val parent = stack.lastOrNull()
        if (parent != null) showPage(parent, forward = false, fromOverview = false)
        else closeToOverview()
    }

    private fun focusedRowIndex(): Int = rows.rows.indexOfFirst { it.view.hasFocus() }.coerceAtLeast(0)

    private fun showPage(page: Page, forward: Boolean, fromOverview: Boolean) {
        preview?.stop()
        preview = null
        val m = Motion.of(prefs)
        // Build the section's rows (a section has at most ~14, so this is quick).
        list.removeAllViews()
        rows = newRowList()
        build(page)
        rows.refreshAll()
        rows.restyle(prefs.accentColor)
        crumb.text = (listOf("SETTINGS") + stack.dropLast(1).map { it.title.uppercase() }).joinToString("  ›  ")
        sectionTitle.text = page.title
        sectionDesc.text = page.desc
        Fonts.apply(panelContent)
        sectionScroll.scrollTo(0, 0)
        showHints()
        val focusRow = { (rows.rows.getOrNull(lastRow[page] ?: 0) ?: rows.rows.firstOrNull())?.view?.requestFocus() }

        if (fromOverview) {
            // Overview recedes; the section panel rises into the centre.
            overview.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            panel.visibility = View.VISIBLE
            val d = m.d(260)
            overview.animate().cancel(); panel.animate().cancel()
            if (d <= 0) {
                overview.alpha = 0f; overview.visibility = View.INVISIBLE
                panel.alpha = 1f; panel.scaleX = 1f; panel.scaleY = 1f
            } else {
                overview.animate().alpha(0f).scaleX(if (m.full) 0.97f else 1f).scaleY(if (m.full) 0.97f else 1f)
                    .setDuration(m.d(200)).setInterpolator(Motion.EASE_IN).withEndAction { overview.visibility = View.INVISIBLE }.start()
                panel.alpha = 0f
                if (m.full) { panel.scaleX = 0.96f; panel.scaleY = 0.96f }
                panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(d).setInterpolator(Motion.EASE_OUT).start()
            }
        } else {
            // Moving between a section and its sub-page: content slides a little in the direction of travel.
            val d = m.d(220)
            if (d > 0) {
                panelContent.animate().cancel()
                panelContent.alpha = 0f
                panelContent.translationX = if (m.full) Ui.dpf(this, if (forward) 28f else -28f) else 0f
                panelContent.animate().alpha(1f).translationX(0f).setDuration(d).setInterpolator(Motion.EASE_OUT).start()
            }
        }
        panel.post { focusRow() }
    }

    private fun closeToOverview() {
        preview?.stop()
        preview = null
        val m = Motion.of(prefs)
        val d = m.d(220)
        overview.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        overview.visibility = View.VISIBLE
        overview.animate().cancel(); panel.animate().cancel()
        val restore = { cards[lastCard]?.requestFocus() }
        if (d <= 0) {
            panel.visibility = View.GONE
            overview.alpha = 1f; overview.scaleX = 1f; overview.scaleY = 1f
            restore()
        } else {
            panel.animate().alpha(0f).scaleX(if (m.full) 0.96f else 1f).scaleY(if (m.full) 0.96f else 1f)
                .setDuration(m.d(180)).setInterpolator(Motion.EASE_IN).withEndAction { panel.visibility = View.GONE }.start()
            overview.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(d).setInterpolator(Motion.EASE_OUT).start()
            restore()
        }
        showHints()
    }

    private fun newRowList() = RowList(this, list).also {
        it.onAnyChange = {
            // Once something changes, sample the live background so the glass matches the preview behind.
            panel.followSnapshot = false
            stylePanel()
            preview?.applyStyle()
            Fonts.apply(panel)
        }
    }

    // ---- Window motion ----

    private fun animateIn() {
        val m = Motion.of(prefs)
        val d = m.d(320)
        if (d <= 0) {
            dim.alpha = DIM
            return
        }
        dim.animate().alpha(DIM).setDuration(d).start()
        overview.alpha = 0f
        if (m.full) { overview.scaleX = 0.97f; overview.scaleY = 0.97f }
        overview.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(d).setInterpolator(Motion.EASE_OUT).start()
    }

    override fun finish() {
        if (closing) return
        closing = true
        val m = Motion.of(prefs)
        val d = m.d(220)
        val done = {
            super.finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        if (d <= 0) return done()
        dim.animate().alpha(0f).setDuration(d).start()
        val target = if (panel.visibility == View.VISIBLE) panel else overview
        target.animate().alpha(0f).scaleX(if (m.full) 0.97f else 1f).scaleY(if (m.full) 0.97f else 1f)
            .setDuration(d).setInterpolator(Motion.EASE_IN).withEndAction(done).start()
    }

    private fun stylePanel() {
        panel.refreshStyle()
        app.glassHub.restyle()
        rows.restyle(prefs.accentColor)
        // Card borders follow the accent colour.
        for (c in cards.values) (c.foreground as? android.graphics.drawable.GradientDrawable)?.let { g ->
            val a = g.alpha
            c.foreground = cardRing().apply { alpha = a }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (menu.isShowing) menu.dismiss() else back()
    }

    // ---- Section contents (every existing setting, grouped) ----

    private fun build(page: Page) = with(rows) {
        val isImage = { prefs.bgType == Prefs.BG_IMAGE }
        val isVideo = { prefs.bgType == Prefs.BG_VIDEO }
        val isGradient = { prefs.bgType == Prefs.BG_GRADIENT }
        val hasWallpaper = { app.exists(ImageFiles.wallpaper(this@SettingsActivity)) }
        val videoName = { java.io.File(prefs.videoPath).let { if (app.exists(it)) it.name else "None — choose in gallery" } }
        val sub = { p: Page -> ActionRow(p.title, { "›" }) { open(p) } }

        when (page) {
            Page.APPEARANCE -> {
                header("Colour")
                add(ChoiceRow("Accent (focus glow)", Prefs.ACCENTS, true, { prefs.accentColor }, { prefs.accentColor = it }, { it }))
                header("Glass")
                add(ChoiceRow("Glass blur", Prefs.GLASS_BLUR, false, { prefs.glassBlur }, { prefs.glassBlur = it }),
                    visibleWhen = { !prefs.lightweight })
                add(ChoiceRow("Glass opacity", Prefs.OPACITY, false, { prefs.glassOpacity }, { prefs.glassOpacity = it }))
                add(ChoiceRow("Glass tint", Prefs.GLASS_TINTS, true, { prefs.glassTint }, { prefs.glassTint = it }, { it }))
                header("Text")
                add(sub(Page.TYPOGRAPHY))
            }
            Page.TYPOGRAPHY -> {
                add(ActionRow("Font", { Fonts.status(this@SettingsActivity) }) { fontInfo() })
                add(ToggleRow("Apple-style fonts (off = Roboto)", { prefs.useCustomFonts }, { prefs.useCustomFonts = it }))
                add(ChoiceRow("Text size", Prefs.TEXT_SCALES, false, { prefs.textScalePct }, { prefs.textScalePct = it }))
            }
            Page.CARDS -> {
                header("Cards")
                add(ChoiceRow("Card size", Prefs.CARD_SIZES, false, { prefs.cardWidthDp }, { prefs.cardWidthDp = it },
                    note = { app.maxFitCardDp.takeIf { it in 1 until prefs.cardWidthDp }?.let { "shown at $it (max for this screen)" } }))
                add(ChoiceRow("Spacing", Prefs.SPACINGS, false, { prefs.spacingDp }, { prefs.spacingDp = it }))
                add(ChoiceRow("Corner radius", Prefs.CORNERS, false, { prefs.cornerDp }, { prefs.cornerDp = it }))
                add(ToggleRow("App names under cards", { prefs.showLabels }, { prefs.showLabels = it }))
                add(ToggleRow("Use TV banner artwork", { prefs.useBanners }, {
                    prefs.useBanners = it
                    app.repo.invalidateAllArtwork()
                }))
                header("Home layout")
                add(ChoiceRow("Screen margins (overscan)", Prefs.SAFE_MARGINS, false, { prefs.safeMarginPct }, {
                    prefs.safeMarginPct = it
                    applySafeArea()
                }))
                add(ToggleRow("App description", { prefs.showDescription }, { prefs.showDescription = it }))
                add(ToggleRow("Remote control hints", { prefs.showHints }, { prefs.showHints = it }))
                add(ActionRow("Remove all custom artwork") {
                    confirm("Remove all custom artwork?", "Cards go back to each app's own artwork.") {
                        ImageFiles.clearDir(this@SettingsActivity, "art")
                        app.repo.invalidateAllArtwork()
                        prefs.touch()
                        toast("Custom artwork removed")
                    }
                })
            }
            Page.WALLPAPER -> {
                header("Wallpaper")
                add(ActionRow("Browse wallpapers…", { "Collection & imported" }) { WallpaperGalleryActivity.open(this@SettingsActivity, null, null) })
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
                header("More")
                add(sub(Page.LOOK))
                add(sub(Page.SCREENSAVER))
            }
            Page.LOOK -> {
                add(ChoiceRow("Brightness", Prefs.BRIGHTNESS, false, { prefs.brightness }, { prefs.brightness = it }))
                add(ChoiceRow("Dimming", Prefs.DIM, false, { prefs.dimPct }, { prefs.dimPct = it }))
                add(ChoiceRow("Blur (images & stills)", Prefs.BLUR, false, { prefs.blur }, { prefs.blur = it }))
                add(ChoiceRow("Overlay colour", Prefs.OVERLAY_COLOURS, true, { prefs.overlayColor }, { prefs.overlayColor = it }, { it }))
                add(ChoiceRow("Overlay opacity", Prefs.OPACITY, false, { prefs.overlayOpacity }, { prefs.overlayOpacity = it }))
                header("Reset")
                add(ActionRow("Remove all per-app backgrounds") {
                    confirm("Remove all per-app backgrounds?", "Apps will use their artwork or the main wallpaper again.") {
                        ImageFiles.clearDir(this@SettingsActivity, "bg")
                        app.clearBackgroundCache()
                        prefs.touch()
                        toast("Per-app backgrounds removed")
                    }
                })
                add(ActionRow("Restore default wallpaper settings") {
                    confirm("Restore default wallpaper?", "Type, colours, position and look settings go back to defaults. Your images and videos are kept.") {
                        prefs.resetBackground()
                        app.clearBackgroundCache()
                        toast("Wallpaper settings restored")
                    }
                })
            }
            Page.SCREENSAVER -> {
                add(ChoiceRow("Start after", Prefs.IDLE_TIMES, false, { prefs.screensaverIdleMin }, { prefs.screensaverIdleMin = it }))
                add(ChoiceRow("Show", Prefs.SS_SOURCES, true, { prefs.screensaverSource }, { prefs.screensaverSource = it }))
                add(ActionRow("Screensaver video", { java.io.File(prefs.screensaverVideo).let { if (app.exists(it)) it.name else "None — choose in gallery" } }) {
                    WallpaperGalleryActivity.open(this@SettingsActivity, null, null)
                }, visibleWhen = { prefs.screensaverSource == Prefs.SS_VIDEO })
                add(ToggleRow("Clock", { prefs.screensaverClock }, { prefs.screensaverClock = it }))
                add(ToggleRow("Greeting", { prefs.screensaverGreeting }, { prefs.screensaverGreeting = it }))
                add(ActionRow("Preview screensaver") { ScreensaverActivity.preview(this@SettingsActivity) })
                add(ActionRow("System screensaver (Google TV)") { systemScreensaverInfo() })
            }
            Page.CLOCK -> {
                addCustom(clockPreview())
                add(ActionRow("Display name", { prefs.displayName.ifBlank { "(none)" } }) { editName() })
                add(ToggleRow("Show clock", { prefs.showClock }, { prefs.showClock = it }))
                add(ChoiceRow("Clock format", Prefs.CLOCK_FORMATS, true, { prefs.clockFormat }, { prefs.clockFormat = it }))
                add(ToggleRow("Show greeting", { prefs.showGreeting }, { prefs.showGreeting = it }))
                add(ToggleRow("Name in greeting", { prefs.greetingName }, { prefs.greetingName = it }), visibleWhen = { prefs.showGreeting })
                add(ToggleRow("Glass backing", { prefs.clockGlass }, { prefs.clockGlass = it }))
                add(ToggleRow("Network status", { prefs.showNetwork }, { prefs.showNetwork = it }))
            }
            Page.MOTION -> {
                add(ChoiceRow("Animations", Prefs.MOTION, false, { prefs.motionLevel }, { prefs.motionLevel = it }))
                add(ChoiceRow("Animation speed", Prefs.SPEEDS, false, { prefs.animSpeedPct }, { prefs.animSpeedPct = it }),
                    visibleWhen = { prefs.motionLevel != Prefs.MOTION_OFF })
                add(ChoiceRow("Visual effects", Prefs.EFFECTS, false, { prefs.effects }, { prefs.effects = it }))
            }
            Page.APPS -> {
                header("Pinned apps")
                add(ActionRow("Pin or unpin apps", { "Open All Apps ›" }) {
                    Ui.startPage(this@SettingsActivity, Intent(this@SettingsActivity, AllAppsActivity::class.java), forward = true)
                })
                add(ActionRow("Reorder pinned apps", { "How ›" }) {
                    menu.show(null, "Reorder pinned apps", listOf(GlassMenu.Item("OK") {}),
                        message = "On Home, hold OK on an app and choose Move, then use ← → and press OK to drop it. " +
                            "Move to start and Remove from Home are in the same menu.")
                })
                add(ActionRow("Reset pinned apps") {
                    confirm("Reset pinned apps?", "Home will go back to the default set of apps.") {
                        prefs.resetPinned()
                        app.repo.refresh()
                        toast("Pinned apps reset")
                    }
                })
                header("All Apps")
                add(ChoiceRow("Columns", Prefs.COLUMN_CHOICES, false, { prefs.columns }, { prefs.columns = it }))
                add(ToggleRow("Include non-TV apps", { prefs.includeNonTvApps }) {
                    prefs.includeNonTvApps = it
                    app.repo.refresh()
                })
            }
            Page.SETUP -> {
                header("Home button")
                add(ActionRow("Home button", { HomeStatus.summary(HomeStatus.check(this@SettingsActivity)) }) { showHomeInfo() })
                add(ActionRow("Android TV settings") {
                    try {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    } catch (e: Exception) {
                        toast("Not available")
                    }
                })
                header("Backup & move")
                add(ActionRow("Export settings", { "Settings, pins, images" }) { exportBackup() })
                add(ActionRow("Import settings", { "Replace with a backup" }) { importBackup() })
                header("Reset")
                add(ActionRow("Reset everything to defaults") {
                    confirm("Reset all settings?", "Every setting returns to its default. Pinned apps, custom artwork, images and fonts are kept.") {
                        prefs.resetAll()
                        app.clearBackgroundCache()
                        app.repo.invalidateAllArtwork()
                        app.repo.refresh()
                        toast("Settings reset")
                    }
                })
            }
            Page.ABOUT -> {
                add(ActionRow("Updates", { updateSummary() }) {
                    startActivity(Intent(this@SettingsActivity, UpdatesActivity::class.java))
                })
                add(ActionRow("Version", { versionName() }) { })
                add(ActionRow("Installation", { "Downloader & sideload ›" }) { installInfo() })
                add(ActionRow("Credits", { "Photos & font ›" }) {
                    menu.show(null, "Credits", listOf(GlassMenu.Item("Close") {}),
                        message = Photos.credits() + "\n\nFont: TeX Gyre Heros, GUST Font License.")
                })
            }
        }
    }

    private fun installInfo() {
        val repo = BuildConfig.UPDATE_REPO
        val link = if (repo.isNotEmpty()) UpdateLogic.latestDownloadUrl(repo, BuildConfig.UPDATE_APK_ASSET) else "(not configured)"
        menu.show(null, "Installation", listOf(GlassMenu.Item("Close") {}),
            message = "App ID: $packageName\n\nLatest stable APK:\n$link\n\nOn the TV: Downloader › enter that link (or its " +
                "AFTVnews code once registered) › Install. Updates afterwards: About & Updates › Updates.")
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
            anchor = null, title = "Display name",
            message = "Shown in the greeting below the clock. Leave blank for no name.",
            field = field,
            items = listOf(GlassMenu.Item("Save") { save() }, GlassMenu.Item("Cancel") {}),
        )
    }

    private fun fontInfo() {
        val dir = Fonts.importDir(this)?.absolutePath
        menu.show(
            null, "Typography",
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
            null, "System screensaver",
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
            anchor = null, title = title, message = message, focusIndex = 1,
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

    private val requestHomeRole = 41

    /** Reports what the Home button really opens, and the options that actually work on this TV. */
    private fun showHomeInfo() {
        val r = HomeStatus.check(this)
        val pkg = packageName
        val adb = "adb shell pm disable-user --user 0 ${HomeStatus.GOOGLE_TV_LAUNCHER}"
        val undo = "adb shell pm enable ${HomeStatus.GOOGLE_TV_LAUNCHER}"
        val msg = when (r.state) {
            HomeStatus.State.ACTIVE ->
                "The Home button opens Quiet Home. ✓\n\nIf the TV starts on an HDMI input or Live TV after power-on, that comes from the " +
                    "TV's own power-on / start-up input setting in its System settings, not from the Home app."
            HomeStatus.State.ROLE_HELD_OVERRIDDEN ->
                "Quiet Home is set as the default Home app, but the Home button still opens Google TV.\n\n" +
                    "Google TV's own launcher has a higher-priority Home entry, and Google TV keeps using it while it is enabled. " +
                    "No app setting can change this. Two ways around it (see README › Making it the Home screen):\n\n" +
                    "• A button-remapping app (e.g. Button Mapper) that sends Home to Quiet Home — no system changes.\n" +
                    "• From a computer, disable Google TV's launcher:\n   $adb\n   Undo any time:\n   $undo"
            HomeStatus.State.NOT_DEFAULT ->
                "The Home button opens ${HomeStatus.summary(r).removePrefix("Opens ")}. Quiet Home works from the Apps row as a normal app.\n\n" +
                    (if (r.canRequestRole) "Choose “Make default” to ask Android to make Quiet Home the Home app. " else "") +
                    (if (r.googleTvLauncherEnabled) "Note: while Google TV's launcher is enabled, Google TV may keep opening it anyway (see README)." else "")
        }
        val items = ArrayList<GlassMenu.Item>()
        val roleIntent = HomeStatus.requestRoleIntent(this)
        if (r.state == HomeStatus.State.NOT_DEFAULT && roleIntent != null) items += GlassMenu.Item("Make default") {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(roleIntent, requestHomeRole)
            } catch (e: Exception) {
                toast("Not available on this TV")
            }
        }
        val homeSettings = Intent(Settings.ACTION_HOME_SETTINGS)
        if (r.state != HomeStatus.State.ACTIVE && homeSettings.resolveActivity(packageManager) != null) {
            items += GlassMenu.Item("Open Home app settings") {
                try {
                    startActivity(homeSettings)
                } catch (e: Exception) {
                    toast("Not available")
                }
            }
        }
        items += GlassMenu.Item("Close") {}
        menu.show(null, "Home button", items, message = msg + "\n\nDetected: Home → ${r.resolvedPackage ?: "none"} · role " +
            (if (r.roleHeld) "held" else "not held") + " · Google TV launcher " + (if (r.googleTvLauncherEnabled) "enabled" else "disabled") +
            " · this app $pkg")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestHomeRole) {
            rows.refreshAll()
            showHomeInfo() // show the re-measured result, not an assumption
        }
    }

    // ---- Backup & move ----

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun exportBackup() {
        toast("Exporting…")
        app.repo.worker.execute {
            val r = runCatching { Backup.export(this) }
            main.post {
                r.onSuccess { places ->
                    menu.show(null, "Settings exported", listOf(GlassMenu.Item("OK") {}),
                        message = "Saved to:\n" + places.joinToString("\n") +
                            "\n\nTo copy it to a computer:\nadb pull ${getExternalFilesDir("backup")?.absolutePath}/${Backup.FILE_NAME}")
                }.onFailure { toast("Export failed: ${it.message}") }
            }
        }
    }

    private fun importBackup() {
        app.repo.worker.execute {
            val others = Backup.otherInstalls(this)
            val ownFile = getExternalFilesDir("backup")?.let { java.io.File(it, Backup.FILE_NAME) }?.takeIf { it.exists() }
            val downloads = Backup.ownDownloads(this)
            main.post {
                val items = ArrayList<GlassMenu.Item>()
                for (pkg in others) items += GlassMenu.Item("From previous Quiet Home") {
                    runRestore("previous Quiet Home") { Backup.importFrom(this, pkg) }
                }
                if (ownFile != null) items += GlassMenu.Item("From backup folder") {
                    runRestore("backup file") { ownFile.inputStream().use { Backup.restore(this, it) } }
                }
                downloads.firstOrNull()?.let { uri -> items += GlassMenu.Item("From Downloads") {
                    runRestore("Downloads") { contentResolver.openInputStream(uri)!!.use { Backup.restore(this, it) } }
                } }
                items += GlassMenu.Item("Cancel") {}
                val dir = getExternalFilesDir("backup")?.absolutePath
                menu.show(null, "Import settings", items, message =
                    (if (others.isEmpty()) "No other Quiet Home install that can hand over its settings was found.\n\n" else "") +
                        "Importing replaces this app's settings, pinned apps, wallpaper, artwork and fonts.\n\n" +
                        "To import a file from a computer:\nadb push ${Backup.FILE_NAME} $dir/")
            }
        }
    }

    private fun runRestore(source: String, op: () -> Backup.Contents) {
        toast("Importing from $source…")
        app.repo.worker.execute {
            val r = runCatching(op)
            main.post {
                r.onSuccess { c ->
                    Backup.applyRestored(app)
                    rows.refreshAll()
                    stylePanel()
                    menu.show(null, "Settings imported", listOf(GlassMenu.Item("OK") {}),
                        message = "${c.pinned} pinned apps, ${c.images} images and ${c.fonts} font files restored.\n\n" +
                            "Video wallpapers or screensaver videos must be copied to this app's folder again (see README).")
                }.onFailure { toast("Import failed: ${it.message ?: "unreadable file"}") }
            }
        }
    }

    private fun toast(msg: String) = GlassMenu.chip(this, root, msg)

    companion object {
        private const val DIM = 0.62f
    }
}
