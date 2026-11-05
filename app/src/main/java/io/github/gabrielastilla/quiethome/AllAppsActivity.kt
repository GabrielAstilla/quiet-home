package io.github.gabrielastilla.quiethome

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Every installed TV app, alphabetical. Pinned apps carry a small accent dot. */
class AllAppsActivity : Activity(), AppAdapter.Listener {

    private lateinit var app: LauncherApp
    private lateinit var prefs: Prefs
    private lateinit var backdrop: BackdropView
    private lateinit var topBar: TopBar
    private lateinit var count: TextView
    private lateinit var grid: RecyclerView
    private lateinit var layoutManager: FocusGridLayoutManager
    private lateinit var adapter: AppAdapter
    private lateinit var root: FrameLayout
    private lateinit var menu: GlassMenu
    private lateinit var idle: IdleTimer

    private var appliedRevision = -1
    private var lastPos = 0
    private var focusOnTabs = false
    private var lastTab: android.view.View? = null

    private val onAppsChanged: () -> Unit = {
        if (render() || currentFocus == null) restoreFocus()
    }
    private val onPrefsChanged: () -> Unit = { applyStyle() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_all_apps)
        app = launcher
        prefs = app.prefs

        backdrop = findViewById(R.id.backdrop)
        count = findViewById(R.id.count)
        grid = findViewById(R.id.grid)
        topBar = TopBar(this, TopBar.APPS)
        topBar.onDown = { layoutManager.focusPosition(grid, lastPos) }
        root = findViewById(R.id.root)
        menu = GlassMenu(this, root, findViewById(R.id.content))
        idle = IdleTimer(this) { !menu.isShowing && hasWindowFocus() }

        layoutManager = FocusGridLayoutManager(this, prefs.columns)
        grid.layoutManager = layoutManager
        grid.itemAnimator = null
        adapter = AppAdapter(app.repo, this)
        adapter.showPinDots = true
        grid.adapter = adapter

        lastPos = savedInstanceState?.getInt(STATE_POS, 0) ?: 0
        applyStyle()
        app.repo.addListener(onAppsChanged)
        prefs.addChangeListener(onPrefsChanged)
        if (app.repo.loaded) {
            render()
            restoreFocus()
        }
    }

    override fun onDestroy() {
        app.repo.removeListener(onAppsChanged)
        prefs.removeChangeListener(onPrefsChanged)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        backdrop.resumeVideo()
    }

    override fun onStop() {
        backdrop.pauseVideo()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        idle.reset()
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        idle.reset()
        topBar.onResume()
        if (appliedRevision != prefs.revision) applyStyle()
        if (app.repo.loaded) {
            render()
            restoreFocus()
        }
    }

    override fun onPause() {
        idle.stop()
        topBar.onPause()
        focusOnTabs = topBar.contains(currentFocus)
        lastTab = if (focusOnTabs) currentFocus else null
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_POS, lastPos)
    }

    override fun finish() {
        super.finish()
        Ui.pageTransition(this, forward = false) // back towards Home (to the left)
    }

    private fun restoreFocus() {
        if (menu.isShowing) return
        if (focusOnTabs || adapter.itemCount == 0) (lastTab ?: topBar.currentTab).requestFocus()
        else layoutManager.focusPosition(grid, lastPos)
    }

    private fun applyStyle() {
        appliedRevision = prefs.revision
        val style = Ui.gridStyle(this, prefs)
        adapter.style = style
        layoutManager.spanCount = style.columns
        grid.layoutParams = grid.layoutParams.also { it.width = style.columns * (style.tileW + style.spacing) }
        val s = BgStyle.from(prefs)
        backdrop.applyStyle(s)
        backdrop.showGlobal(app, s, 0)
        // The backdrop is opaque and full-screen; a window background would only add overdraw.
        window.setBackgroundDrawable(null)
        topBar.applyStyle()
        val hadCardFocus = grid.hasFocus()
        adapter.notifyDataSetChanged()
        if (hadCardFocus) layoutManager.focusPosition(grid, lastPos)
        Fonts.apply(findViewById(R.id.content))
    }

    private fun render(): Boolean {
        adapter.pinnedKeys = prefs.pinned.toHashSet()
        val apps = app.repo.apps
        val changed = adapter.submit(apps)
        if (!changed) adapter.refreshState()
        count.text = (if (apps.size == 1) "1 app" else "${apps.size} apps") + "  ·  Hold OK on an app for options"
        return changed
    }

    override fun onAppClick(entry: AppEntry, position: Int) = app.repo.launch(this, entry)

    override fun onAppFocused(entry: AppEntry, position: Int) {
        lastPos = position
        focusOnTabs = false
    }

    override fun onUpFromTopRow(): Boolean {
        topBar.currentTab.requestFocus()
        return true
    }

    override fun onAppLongClick(entry: AppEntry, position: Int) {
        val pinned = entry.key in prefs.pinned
        val options = listOf(
            GlassMenu.Item(if (pinned) "Remove from Home" else "Add to Home") { togglePin(entry) },
            GlassMenu.Item("Open") { app.repo.launch(this, entry) },
            GlassMenu.Item("Change artwork…") { PickImageActivity.start(this, PickImageActivity.TARGET_ART, entry) },
            GlassMenu.Item("Set background…") { PickImageActivity.start(this, PickImageActivity.TARGET_APP_BG, entry) },
            GlassMenu.Item("App info") { app.repo.openAppInfo(this, entry.packageName) },
        )
        menu.show(layoutManager.findViewByPosition(position), entry.label, options)
    }

    private fun togglePin(entry: AppEntry) {
        val current = prefs.pinned
        val nowPinned = entry.key !in current
        prefs.pinned = if (nowPinned) current + entry.key else current - entry.key
        adapter.pinnedKeys = prefs.pinned.toHashSet()
        adapter.refreshState()
        GlassMenu.chip(this, root, if (nowPinned) "${entry.label} added to Home" else "${entry.label} removed from Home")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (menu.isShowing) {
            menu.dismiss()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    companion object {
        private const val STATE_POS = "focus_pos"
    }
}
