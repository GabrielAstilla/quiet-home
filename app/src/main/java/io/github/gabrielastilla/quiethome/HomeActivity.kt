package io.github.gabrielastilla.quiethome

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * Home: top navigation, hero (selected app's name + details), background that follows the
 * selection, a horizontal carousel of pinned apps, and remote hints.
 *
 * Focus rules:
 *  - Up from the carousel goes to the HOME tab; Down from any tab returns to the carousel.
 *  - Returning from an app or closing Settings restores the exact card (or tab) you left.
 *  - Pressing Home while already here jumps back to the first app.
 */
class HomeActivity : Activity(), AppAdapter.Listener {

    private lateinit var app: LauncherApp
    private lateinit var prefs: Prefs
    private lateinit var backdrop: BackdropView
    private lateinit var topBar: TopBar
    private lateinit var hero: View
    private lateinit var heroTitle: TextView
    private lateinit var heroVersion: TextView
    private lateinit var heroDesc: TextView
    private lateinit var row: RecyclerView
    private lateinit var hints: LinearLayout
    private lateinit var root: FrameLayout
    private lateinit var empty: View
    private lateinit var emptyButton: TextView
    private lateinit var menu: GlassMenu
    private var moveStartPos = -1
    private lateinit var idle: IdleTimer
    private lateinit var layoutManager: FocusRowLayoutManager
    private lateinit var adapter: AppAdapter
    private val main = Handler(Looper.getMainLooper())

    private var appliedRevision = -1
    private var motion = Motion(Prefs.MOTION_FULL, 100)
    private var bgStyle: BgStyle? = null
    private var lastPos = 0
    private var focusOnTabs = false
    private var lastTab: View? = null
    private var isForeground = false

    private var movePos = -1
    private var moveOkDown = false

    // Hero crossfade state: the newest selection always wins; nothing is queued.
    private var heroTarget: AppEntry? = null
    private var heroShownKey: String? = "\u0000"
    private var heroFadingOut = false

    private var bgTarget: AppEntry? = null
    private val bgTask = Runnable { applyBackground(bgTarget) }

    private val onAppsChanged: () -> Unit = {
        val changed = renderPinned()
        if (changed || currentFocus == null) restoreFocus()
        // force: refresh in place when the selection is unchanged (e.g. app list just finished loading).
        setHero(adapter.entryAt(lastPos), force = true)
    }

    private val onPrefsChanged: () -> Unit = { applyStyle() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        app = launcher
        prefs = app.prefs

        backdrop = findViewById(R.id.backdrop)
        hero = findViewById(R.id.hero)
        heroTitle = findViewById(R.id.hero_title)
        heroVersion = findViewById(R.id.hero_version)
        heroDesc = findViewById(R.id.hero_desc)
        row = findViewById(R.id.row)
        hints = findViewById(R.id.hints)
        root = findViewById(R.id.root)
        empty = findViewById(R.id.empty)
        emptyButton = findViewById(R.id.empty_button)
        menu = GlassMenu(this, root, findViewById(R.id.content))
        idle = IdleTimer(this) { isForeground && !menu.isShowing && movePos < 0 && hasWindowFocus() }
        findViewById<GlassView>(R.id.empty_glass).capsule = true
        emptyButton.setOnClickListener { Ui.startPage(this, Intent(this, AllAppsActivity::class.java), forward = true) }
        emptyButton.setOnFocusChangeListener { v, f ->
            (v as TextView).setTextColor(if (f) 0xFF0B1222.toInt() else android.graphics.Color.WHITE)
            if (f && hasWindowFocus()) focusOnTabs = false
            updateHints()
        }
        emptyButton.setOnKeyListener { _, code, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_UP) {
                topBar.currentTab.requestFocus()
                true
            } else ev.action == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_DOWN)
        }
        // The clock + greeting block on the right is taller than the navigation on the left. Let the hero
        // start just below the navigation (left side) instead of below the clock, so nothing is pushed off screen.
        val topBarView = findViewById<View>(R.id.top_bar)
        val nav = findViewById<View>(R.id.nav_glass)
        topBarView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val overflow = (topBarView.height - topBarView.paddingBottom - nav.bottom).coerceAtLeast(0)
            val lp = hero.layoutParams as LinearLayout.LayoutParams
            val want = Ui.dp(this, 26) - overflow
            if (lp.topMargin != want) {
                lp.topMargin = want
                hero.post { hero.layoutParams = lp }
            }
        }
        // Re-fit the carousel whenever the available height changes.
        findViewById<View>(R.id.content).addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) findViewById<View>(R.id.content).post { refit() }
        }
        hints.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) hints.post {
                applySafeMargins()
                refit()
            }
        }
        // Soft shadows keep white text readable over light wallpapers.
        val sh = 0x66000000
        heroTitle.setShadowLayer(Ui.dpf(this, 14f), 0f, Ui.dpf(this, 2f), sh)
        for (t in listOf(heroVersion, heroDesc)) t.setShadowLayer(Ui.dpf(this, 8f), 0f, Ui.dpf(this, 1f), sh)

        topBar = TopBar(this, TopBar.HOME)
        topBar.onDown = {
            if (adapter.itemCount == 0) emptyButton.requestFocus() else layoutManager.focusPosition(row, lastPos)
            true
        }
        topBar.onFocusChanged = { onTabs ->
            // Ignore focus shuffles while a dialog/panel covers Home (e.g. during a live restyle).
            if (hasWindowFocus()) {
                focusOnTabs = onTabs
                updateHints()
            }
        }

        layoutManager = FocusRowLayoutManager(this)
        row.layoutManager = layoutManager
        adapter = AppAdapter(app.repo, this)
        row.adapter = adapter

        if (BuildConfig.DEBUG) RedrawProbe.attach(window.decorView, "Home")
        lastPos = savedInstanceState?.getInt(STATE_POS, 0) ?: 0
        applyStyle()
        app.repo.addListener(onAppsChanged)
        prefs.addChangeListener(onPrefsChanged)
        if (app.repo.loaded) {
            renderPinned()
            restoreFocus()
            setHero(adapter.entryAt(lastPos), force = true)
        }
    }

    override fun onDestroy() {
        app.repo.removeListener(onAppsChanged)
        prefs.removeChangeListener(onPrefsChanged)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        backdrop.resumeVideo()
    }

    override fun onStop() {
        backdrop.pauseVideo() // never decode video while an app is in front
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        idle.reset()
        // Optional daily update check (off by default). It only notifies; nothing downloads by itself.
        main.postDelayed({
            app.updater.maybeAutoCheck { v ->
                if (isForeground) GlassMenu.chip(this, root, "Update available: Quiet Home $v · Settings › About & Updates › Updates")
            }
        }, 4000)
        topBar.onResume()
        // The guide is hidden while Settings (a translucent overlay with its own guide) is on top.
        hints.animate().alpha(1f).setDuration(Motion.of(prefs).d(180)).start()
        if (appliedRevision != prefs.revision) applyStyle()
        if (app.repo.loaded) {
            renderPinned()
            restoreFocus()
            setHero(adapter.entryAt(lastPos), force = true)
        }
    }

    override fun onPause() {
        isForeground = false
        idle.stop()
        topBar.onPause()
        hints.animate().alpha(0f).setDuration(Motion.of(prefs).d(150)).start()
        focusOnTabs = topBar.contains(currentFocus)
        lastTab = if (focusOnTabs) currentFocus else null
        if (movePos >= 0) endMove()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_POS, lastPos)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already showing: back to the first app.
        if (isForeground) {
            if (movePos >= 0) endMove()
            focusOnTabs = false
            lastTab = null
            lastPos = 0
            restoreFocus()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            menu.isShowing -> menu.dismiss()
            focusOnTabs -> layoutManager.focusPosition(row, lastPos)
            // As the Home app, Back has nowhere to go. Opened as a normal app, Back exits.
            !HomeStatus.isActive(this) -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    // ---- Styling ----

    private fun applyStyle() {
        appliedRevision = prefs.revision
        motion = Motion.of(prefs)
        applySafeMargins()
        val style = fittedStyle()
        adapter.style = style
        layoutManager.motion = motion
        row.itemAnimator = if (motion.enabled) {
            DefaultItemAnimator().apply {
                supportsChangeAnimations = false
                moveDuration = motion.d(220)
                addDuration = motion.d(160)
                removeDuration = motion.d(140)
            }
        } else null
        // Vertical padding leaves room for the enlarged card, its ring and its shadow so nothing clips.
        val (padTop, padBottom) = Ui.rowPadding(this, style)
        row.setPadding(Ui.dp(this, 43), padTop, Ui.dp(this, 43), padBottom)
        emptyButton.background = Ui.tabBackground(this, prefs.accentColor, current = true)

        val s = BgStyle.from(prefs)
        bgStyle = s
        backdrop.applyStyle(s)
        // The backdrop is opaque and full-screen; a window background would only add overdraw.
        window.setBackgroundDrawable(null)
        topBar.applyStyle()
        updateHints()
        val hadCardFocus = row.hasFocus()
        adapter.notifyDataSetChanged()
        if (hadCardFocus) layoutManager.focusPosition(row, lastPos)
        Fonts.apply(findViewById(R.id.content))
        findViewById<View>(R.id.content).post { refit() }
        idle.reset()
        // Background inputs (blur, images, mode) may have changed: re-resolve without fading.
        applyBackground(heroTarget, instant = true)
        bindHero(heroTarget)
    }

    // ---- Layout fitting ----
    //
    // The screen is divided top to bottom: navigation + clock, the app title area, the carousel, and a
    // dedicated bottom area for the remote guide. The carousel gets whatever height is left, and the card
    // size is capped so the focused card (scaled, lifted, ringed, with shadow) always fits in that height.
    // Nothing uses fixed vertical positions, so this holds at 720p, 1080p, 4K and any density or text size.

    private fun applySafeMargins() {
        val dm = resources.displayMetrics
        val pct = prefs.safeMarginPct / 100f
        val content = findViewById<View>(R.id.content)
        val mx = (dm.widthPixels * pct).toInt()
        val my = (dm.heightPixels * pct).toInt()
        // The guide sits in its own bottom area, inside the TV-safe margin (27dp ≈ 5% of 540dp) plus any extra.
        val hlp = hints.layoutParams as android.widget.FrameLayout.LayoutParams
        val guideBottom = Ui.dp(this, 27) + my
        if (hlp.bottomMargin != guideBottom || hlp.leftMargin != mx) {
            hlp.bottomMargin = guideBottom
            hlp.leftMargin = mx
            hlp.rightMargin = mx
            hints.layoutParams = hlp
        }
        // Content above reserves the guide's full height, so nothing above can overlap or push it.
        val reserve = maxOf(hints.height, Ui.dp(this, 28)) + guideBottom + Ui.dp(this, 6)
        if (content.paddingLeft != mx || content.paddingTop != my || content.paddingBottom != reserve) {
            content.setPadding(mx, my, mx, reserve)
        }
    }

    private fun labelHeight(): Int =
        (16f * resources.displayMetrics.scaledDensity * prefs.textScalePct / 100f * 1.35f).toInt() + Ui.dp(this, 6)

    /**
     * Compaction for unusually small screens (high density, big text, large margins):
     * 0 = normal, 1 = hide description, 2 = also shrink the title, 3 = also drop names under cards.
     */
    private var compact = 0

    private fun applyCompact(level: Int) {
        compact = level
        heroDesc.visibility = if (level >= 1 || !prefs.showDescription) View.GONE else View.VISIBLE
        val base = (heroTitle.getTag(R.id.font_base_size) as? Float) ?: heroTitle.textSize
        val scale = prefs.textScalePct / 100f * (if (level >= 2) 0.72f else 1f)
        heroTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, base * scale)
    }

    /** Height available for the carousel, or null before the first layout. */
    private fun availableRowHeight(level: Int = compact): Int? {
        val content = findViewById<View>(R.id.content)
        val topBarView = findViewById<View>(R.id.top_bar)
        val nav = findViewById<View>(R.id.nav_glass)
        if (content.height == 0 || nav.height == 0) return null
        val inner = content.height - content.paddingTop - content.paddingBottom
        // The title area starts just under the navigation (it may sit beside the taller clock block).
        val heroTop = topBarView.paddingTop + nav.height + Ui.dp(this, 26)
        val desc = if (prefs.showDescription && level < 1) heroDesc.lineHeight + Ui.dp(this, 6) else 0
        // Measured title line height (real font metrics), converted to the compaction level being tested.
        val normalLine = heroTitle.lineHeight / (if (compact >= 2) 0.72f else 1f)
        val titleLine = (normalLine * (if (level >= 2) 0.72f else 1f)).toInt()
        val heroH = titleLine + desc + Ui.dp(this, 6)
        // The guide's space is already excluded via the content's bottom padding.
        return inner - heroTop - heroH
    }

    /** The requested card size, capped to the largest size whose carousel fits the available height. */
    private fun fittedStyle(): TileStyle {
        val requested0 = Ui.rowStyle(this, prefs)
        // Pick the lightest compaction under which even the smallest card fits.
        val minStyle = Ui.rowStyle(this, prefs, maxW = Ui.dp(this, 64))
        var level = 0
        while (level < 3) {
            val a = availableRowHeight(level) ?: break
            val lbl = if (level >= 3) 0 else labelHeight()
            if (Ui.rowHeight(this, minStyle.copy(showLabels = minStyle.showLabels && level < 3), lbl) <= a) break
            level++
        }
        applyCompact(level) // always re-apply: restyling resets text sizes
        val requested = if (level >= 3) requested0.copy(showLabels = false) else requested0
        val avail = availableRowHeight() ?: return requested
        val label = labelHeight()
        fun fits(widthDp: Int) = Ui.rowHeight(this, Ui.rowStyle(this, prefs, widthDp = widthDp), label) <= avail
        // Largest valid size from the supported list (reported in Settings).
        app.maxFitCardDp = Prefs.CARD_SIZES.map { it.value }.filter { fits(it) }.maxOrNull() ?: Prefs.CARD_SIZES.first().value
        if (Ui.rowHeight(this, requested, label) <= avail) return requested
        // Binary-search the widest card (px) that fits.
        var lo = Ui.dp(this, 64)
        var hi = requested.tileW
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (Ui.rowHeight(this, Ui.rowStyle(this, prefs, maxW = mid), label) <= avail) lo = mid else hi = mid - 1
        }
        val fitted = Ui.rowStyle(this, prefs, maxW = lo)
        return if (level >= 3) fitted.copy(showLabels = false) else fitted
    }

    /** Re-fits after the layout settles (first measure, resolution, text size or margin changes). */
    private fun refit() {
        val style = fittedStyle()
        val cur = adapter.style
        if (style.tileW != cur.tileW || style.cardH != cur.cardH) {
            val m = Motion.of(prefs)
            if (m.enabled && row.isLaidOut) {
                android.transition.TransitionManager.beginDelayedTransition(
                    findViewById(R.id.content),
                    android.transition.ChangeBounds().setDuration(m.d(260)).setInterpolator(Motion.EASE_OUT).excludeChildren(row, true),
                )
            }
            adapter.style = style
            val (t, b) = Ui.rowPadding(this, style)
            row.setPadding(Ui.dp(this, 43), t, Ui.dp(this, 43), b)
            val hadCardFocus = row.hasFocus()
            adapter.notifyDataSetChanged()
            if (hadCardFocus) layoutManager.focusPosition(row, lastPos)
        }
        // If there's spare height, let the description use a second line.
        val spare = (availableRowHeight() ?: 0) - Ui.rowHeight(this, adapter.style, labelHeight())
        heroDesc.maxLines = if (spare >= heroDesc.lineHeight) 2 else 1
        if (compact == 0 && prefs.showDescription) heroDesc.visibility = View.VISIBLE
    }

    private fun updateHints() {
        val list = when {
            movePos >= 0 -> listOf("← →" to "Move", "OK" to "Drop", "BACK" to "Done")
            !prefs.showHints -> emptyList()
            focusOnTabs -> listOf("OK" to "Select", "↓" to "Apps")
            adapter.itemCount == 0 -> listOf("OK" to "Open All Apps", "↑" to "Menu")
            else -> listOf("OK" to "Open", "Hold OK" to "Options", "↑" to "Menu")
        }
        Ui.setHints(hints, list)
    }

    /** Returns true when the visible list changed. */
    private fun renderPinned(): Boolean {
        val repo = app.repo
        if (!repo.loaded) return false
        if (!prefs.hasPinnedList) prefs.pinned = repo.defaultPins()
        val changed = adapter.submit(prefs.pinned.mapNotNull { repo.find(it) })
        val none = adapter.itemCount == 0
        empty.visibility = if (none) View.VISIBLE else View.GONE
        row.visibility = if (none) View.GONE else View.VISIBLE
        updateHints()
        return changed
    }

    // ---- Focus ----

    private fun restoreFocus() {
        if (menu.isShowing) return
        if (focusOnTabs) {
            (lastTab ?: topBar.currentTab).requestFocus()
            return
        }
        if (adapter.itemCount == 0) {
            emptyButton.requestFocus()
            return
        }
        layoutManager.focusPosition(row, lastPos)
    }

    override fun onUpFromTopRow(): Boolean {
        topBar.currentTab.requestFocus()
        return true
    }

    // ---- Hero (title, subtitle, details) ----

    /** Crossfades the hero to [entry]. Rapid calls retarget: the latest entry is what gets shown. */
    private fun setHero(entry: AppEntry?, force: Boolean = false) {
        heroTarget = entry
        if (entry?.key == heroShownKey && !heroFadingOut) {
            if (force) bindHero(entry) // same app: refresh details in place, no fade
            return
        }
        if (!motion.enabled || force && hero.alpha == 1f && heroShownKey == "\u0000") {
            hero.animate().cancel()
            heroFadingOut = false
            hero.alpha = 1f
            hero.translationY = 0f
            bindHero(entry)
            scheduleBackground(entry, 0)
            return
        }
        if (heroFadingOut) return // the running fade-out will bind the newest target
        heroFadingOut = true
        hero.animate().cancel()
        hero.animate().withLayer().alpha(0f).setDuration((motion.d(90) * hero.alpha).toLong()).setInterpolator(Motion.EASE_IN)
            .withEndAction {
                heroFadingOut = false
                val target = heroTarget
                bindHero(target)
                scheduleBackground(target, 60)
                hero.translationY = if (motion.full) Ui.dpf(this, 6f) else 0f
                hero.animate().withLayer().alpha(1f).translationY(0f).setDuration(motion.d(180)).setInterpolator(Motion.EASE_OUT).start()
            }.start()
    }

    private fun bindHero(entry: AppEntry?) {
        heroShownKey = entry?.key
        heroDesc.visibility = if (prefs.showDescription && compact < 1) View.VISIBLE else View.GONE
        if (entry == null) {
            heroTitle.text = if (app.repo.loaded) "Pin your favourite apps" else ""
            heroVersion.text = ""
            heroDesc.visibility = View.VISIBLE
            heroDesc.text = if (app.repo.loaded) "Your home screen is empty. Pick the apps you use most and they'll appear here." else ""
            return
        }
        heroTitle.text = entry.label
        app.repo.loadDetails(entry) { d ->
            if (heroShownKey != entry.key) return@loadDetails
            if (!prefs.showDescription) heroDesc.visibility = View.GONE
            heroVersion.text = d.version ?: ""
            heroDesc.text = d.description ?: ""
        }
    }



    // ---- Background ----

    /** Debounced so holding Left/Right doesn't decode a background for every card passed. */
    private fun scheduleBackground(entry: AppEntry?, delayMs: Long) {
        bgTarget = entry
        main.removeCallbacks(bgTask)
        if (delayMs <= 0) bgTask.run() else main.postDelayed(bgTask, delayMs)
    }

    private fun applyBackground(entry: AppEntry?, instant: Boolean = false) {
        val s = bgStyle ?: return
        val d = if (instant) 0L else motion.d(320)
        if (entry != null && s.perApp != Prefs.PER_APP_OFF) {
            val custom = ImageFiles.appBackground(this, entry.key)
            if (app.exists(custom)) {
                val seq = backdrop.beginRequest()
                val scale = BgStyle.scaleType(prefs.appBgPosition(entry.key))
                app.loadBackground(custom, s.blur) { t, bmp -> if (backdrop.isCurrent(seq)) backdrop.show(t, bmp, scale, d) }
                return
            }
            if (s.perApp == Prefs.PER_APP_CUSTOM_AND_ART) {
                val seq = backdrop.beginRequest()
                app.repo.loadBackdrop(entry) { bmp ->
                    if (backdrop.isCurrent(seq)) backdrop.show("art:${entry.key}", bmp, ImageView.ScaleType.CENTER_CROP, d)
                }
                return
            }
        }
        backdrop.showGlobal(app, s, d)
    }

    // ---- Card callbacks ----

    override fun onAppClick(entry: AppEntry, position: Int) {
        if (movePos < 0) app.repo.launch(this, entry)
    }

    override fun onAppFocused(entry: AppEntry, position: Int) {
        lastPos = position
        if (hasWindowFocus()) focusOnTabs = false
        updateHints()
        if (movePos < 0) setHero(entry)
    }

    override fun onAppLongClick(entry: AppEntry, position: Int) {
        if (movePos >= 0) return
        val hasArt = app.exists(ImageFiles.appArtwork(this, entry.key))
        val hasBg = app.exists(ImageFiles.appBackground(this, entry.key))
        val options = ArrayList<GlassMenu.Item>()
        fun add(label: String, action: () -> Unit) { options += GlassMenu.Item(label, action) }
        add("Open") { app.repo.launch(this, entry) }
        add("Move") { adapter.indexOf(entry.key).takeIf { it >= 0 }?.let(::startMove) }
        add("Move to start") {
            val p = adapter.indexOf(entry.key)
            if (p > 0) {
                adapter.move(p, 0)
                savePinnedOrder()
                lastPos = 0
                restoreFocus()
                GlassMenu.chip(this, root, "Moved to start")
            }
        }
        add("Remove from Home") { unpin(entry) }
        add("Change artwork…") { PickImageActivity.start(this, PickImageActivity.TARGET_ART, entry) }
        if (hasArt) add("Reset artwork") { resetArtwork(entry) }
        add("Set background…") { PickImageActivity.start(this, PickImageActivity.TARGET_APP_BG, entry) }
        if (hasBg) add("Remove background") { removeAppBackground(entry) }
        add("App info") { app.repo.openAppInfo(this, entry.packageName) }
        menu.show(layoutManager.findViewByPosition(position), entry.label, options)
    }

    private fun resetArtwork(entry: AppEntry) {
        ImageFiles.appArtwork(this, entry.key).delete()
        app.repo.invalidateApp(entry.key)
        prefs.touch()
    }

    private fun removeAppBackground(entry: AppEntry) {
        ImageFiles.appBackground(this, entry.key).delete()
        app.clearBackgroundCache()
        prefs.touch()
    }

    private fun unpin(entry: AppEntry) {
        prefs.pinned = prefs.pinned - entry.key
        renderPinned()
        restoreFocus()
        setHero(adapter.entryAt(lastPos.coerceAtMost(adapter.itemCount - 1)))
    }

    // ---- Move mode ----

    private fun startMove(pos: Int) {
        movePos = pos
        moveStartPos = pos
        moveOkDown = false
        adapter.setMoving(pos)
        updateHints()
    }

    private fun moveBy(delta: Int) {
        val to = (movePos + delta).coerceIn(0, adapter.itemCount - 1)
        if (to == movePos) return
        adapter.move(movePos, to) // neighbours animate into place via the item animator
        movePos = to
        lastPos = to
        layoutManager.focusPosition(row, to)
    }

    private fun endMove() {
        val moved = movePos != moveStartPos
        movePos = -1
        adapter.setMoving(-1)
        savePinnedOrder()
        updateHints()
        if (moved && isForeground) GlassMenu.chip(this, root, "Position saved")
    }

    private fun savePinnedOrder() {
        val visible = adapter.keys()
        val visibleSet = visible.toHashSet()
        // Keep pins for apps that are temporarily missing (e.g. mid-update) at the end.
        prefs.pinned = visible + prefs.pinned.filterNot { it in visibleSet }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        idle.reset()
        if (movePos < 0 || menu.isShowing) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) moveBy(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) moveBy(1)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> Unit
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                if (down) moveOkDown = true else if (moveOkDown) endMove()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (!down) endMove()
            else -> return super.dispatchKeyEvent(event) // volume etc. still work
        }
        return true
    }

    companion object {
        private const val STATE_POS = "focus_pos"
    }
}
