package com.zguy.tvlauncher

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.format.Formatter
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * Settings › About › Updates. Every control is a focusable button; Left/Right moves between buttons,
 * Up/Down reaches the release notes (which scroll) and the automatic-check toggle.
 */
class UpdatesActivity : Activity() {

    private lateinit var app: LauncherApp
    private lateinit var updater: Updater
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var notesTitle: TextView
    private lateinit var notesScroll: ScrollView
    private lateinit var notes: TextView
    private lateinit var btnCheck: TextView
    private lateinit var btnDownload: TextView
    private lateinit var btnInstall: TextView
    private lateinit var btnAuto: TextView
    private lateinit var menu: GlassMenu

    private val listener: (Updater.State) -> Unit = { render(it) }
    private var lastKind: Class<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_updates)
        app = launcher
        updater = app.updater
        val prefs = app.prefs

        val backdrop = findViewById<BackdropView>(R.id.backdrop)
        val s = BgStyle.from(prefs)
        backdrop.applyStyle(s)
        backdrop.showGlobal(app, s, 0)
        findViewById<GlassView>(R.id.panel).apply {
            cornerRadius = Ui.dpf(this@UpdatesActivity, 28f)
            opacityBoost = 0.3f
        }
        menu = GlassMenu(this, findViewById(R.id.root), findViewById(R.id.content))

        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        progressText = findViewById(R.id.progress_text)
        notesTitle = findViewById(R.id.notes_title)
        notesScroll = findViewById(R.id.notes_scroll)
        notes = findViewById(R.id.notes)
        btnCheck = findViewById(R.id.btn_check)
        btnDownload = findViewById(R.id.btn_download)
        btnInstall = findViewById(R.id.btn_install)
        btnAuto = findViewById(R.id.btn_auto)

        findViewById<TextView>(R.id.installed).text =
            "Installed: Quiet Home ${updater.installedVersionName} (version code ${updater.installedVersionCode})"
        findViewById<TextView>(R.id.source).text = if (updater.configured) {
            "Source: github.com/${updater.repo} · stable releases only · nothing installs without your confirmation"
        } else {
            "Updates aren't configured in this build: no GitHub repository is set (see RELEASING.md)."
        }

        for (b in listOf(btnCheck, btnDownload, btnInstall, btnAuto)) {
            b.background = Ui.focusBackground(this, prefs.accentColor, 22, 0.10f)
            b.setOnFocusChangeListener { v, f ->
                (v as TextView).setTextColor(if (f) Color.WHITE else 0xE6FFFFFF.toInt())
                // Wide rows scale less so they stay inside the panel.
                Ui.focusScale(v, f, Motion.of(prefs), if (v === btnAuto) 1.015f else 1.04f)
            }
        }
        notesScroll.background = Ui.focusBackground(this, prefs.accentColor, 14, 0.04f)

        btnCheck.setOnClickListener { updater.check() }
        btnDownload.setOnClickListener {
            if (updater.state is Updater.State.Downloading) updater.cancel() else updater.download()
        }
        btnInstall.setOnClickListener { install() }
        btnAuto.setOnClickListener {
            prefs.autoUpdateCheck = !prefs.autoUpdateCheck
            renderAuto()
        }
        // Up/Down inside the notes scrolls them; at the ends, focus moves on as usual.
        notesScroll.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val step = Ui.dp(this, 60)
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> if (notesScroll.canScrollVertically(1)) { notesScroll.smoothScrollBy(0, step); true } else false
                KeyEvent.KEYCODE_DPAD_UP -> if (notesScroll.canScrollVertically(-1)) { notesScroll.smoothScrollBy(0, -step); true } else false
                else -> false
            }
        }
        renderAuto()
        Fonts.apply(findViewById(R.id.content))
        btnCheck.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        updater.refreshPermission() // returning from the "Install unknown apps" screen
        updater.addListener(listener)
    }

    override fun onPause() {
        updater.removeListener(listener)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (menu.isShowing) menu.dismiss() else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun renderAuto() {
        btnAuto.text = "Check automatically once a day:  " + if (app.prefs.autoUpdateCheck) "On" else "Off"
    }

    private fun enable(b: TextView, on: Boolean, visible: Boolean = true) {
        b.visibility = if (visible) View.VISIBLE else View.GONE
        b.isEnabled = on
        b.isFocusable = on
        b.alpha = if (on) 1f else 0.38f
    }

    private fun render(s: Updater.State) {
        val focusLost = currentFocus?.let { !it.isEnabled || it.visibility != View.VISIBLE } ?: true
        var info: Updater.UpdateInfo? = null
        progress.visibility = View.GONE
        progressText.visibility = View.GONE
        btnDownload.text = getString(R.string.download_update)
        when (s) {
            Updater.State.NotConfigured -> {
                status.text = "Updates aren't set up for this build yet."
                enable(btnCheck, false); enable(btnDownload, false); enable(btnInstall, false)
            }
            Updater.State.Idle -> {
                status.text = "Check GitHub for a newer stable release."
                enable(btnCheck, true); enable(btnDownload, false); enable(btnInstall, false)
            }
            Updater.State.Checking -> {
                status.text = "Checking for updates…"
                progress.visibility = View.VISIBLE
                progress.isIndeterminate = true
                enable(btnCheck, false); enable(btnDownload, false); enable(btnInstall, false)
            }
            is Updater.State.UpToDate -> {
                status.text = "Quiet Home is up to date." + (s.latestName?.let { "  Latest release: $it" } ?: "  No stable releases published yet.")
                enable(btnCheck, true); enable(btnDownload, false); enable(btnInstall, false)
            }
            is Updater.State.Available -> {
                info = s.info
                status.text = "Update available: Quiet Home ${s.info.versionName}" +
                    (if (s.info.metadata == null) "  (version checked after download)" else "")
                enable(btnCheck, true); enable(btnDownload, true); enable(btnInstall, false)
            }
            is Updater.State.Downloading -> {
                info = s.info
                status.text = "Downloading Quiet Home ${s.info.versionName}…"
                progress.visibility = View.VISIBLE
                progressText.visibility = View.VISIBLE
                if (s.total > 0) {
                    progress.isIndeterminate = false
                    progress.progress = (s.bytes * 1000 / s.total).toInt()
                    progressText.text = "${Formatter.formatShortFileSize(this, s.bytes)} of ${Formatter.formatShortFileSize(this, s.total)}"
                } else {
                    progress.isIndeterminate = true
                    progressText.text = Formatter.formatShortFileSize(this, s.bytes)
                }
                btnDownload.text = getString(R.string.cancel_download)
                enable(btnCheck, false); enable(btnDownload, true); enable(btnInstall, false)
            }
            is Updater.State.Ready -> {
                info = s.info
                status.text = "Quiet Home ${s.versionName} is downloaded and verified. Choose Install Update."
                enable(btnCheck, true); enable(btnDownload, false); enable(btnInstall, true)
            }
            is Updater.State.NeedsPermission -> {
                info = s.info
                status.text = "Quiet Home needs permission to install apps. Choose Install Update to open the setting."
                enable(btnCheck, true); enable(btnDownload, false); enable(btnInstall, true)
            }
            is Updater.State.Installing -> {
                info = s.info
                status.text = "Opening Android's installer… confirm the update there."
                enable(btnCheck, false); enable(btnDownload, false); enable(btnInstall, false)
            }
            is Updater.State.Error -> {
                status.text = s.message
                btnDownload.text = getString(R.string.retry_download)
                enable(btnCheck, true); enable(btnDownload, s.canRetryDownload); enable(btnInstall, false)
            }
        }
        val n = info?.notes.orEmpty()
        notesTitle.visibility = if (n.isNotEmpty()) View.VISIBLE else View.GONE
        notesScroll.visibility = notesTitle.visibility
        notes.text = plainNotes(n)
        notesScroll.isFocusable = n.isNotEmpty()
        // Move focus to the natural next step when the state changes (Download → Install, Retry after failure).
        val changed = lastKind != null && lastKind != s.javaClass
        lastKind = s.javaClass
        val next = when (s) {
            is Updater.State.Available -> btnDownload
            is Updater.State.Ready, is Updater.State.NeedsPermission -> btnInstall
            is Updater.State.Error -> if (s.canRetryDownload) btnDownload else btnCheck
            else -> null
        }
        if (changed && next != null && next.isEnabled) next.requestFocus()
        else if (focusLost) {
            listOf(btnInstall, btnDownload, btnCheck, btnAuto).firstOrNull { it.isEnabled && it.visibility == View.VISIBLE }?.requestFocus()
        }
    }

    /** GitHub release notes are Markdown; show headings and bullets cleanly as plain text. */
    private fun plainNotes(md: String): String = md.lines().joinToString("\n") { line ->
        val t = line.trimEnd()
        when {
            t.matches(Regex("^#{1,6}\\s+.*")) -> t.replace(Regex("^#{1,6}\\s+"), "")
            t.matches(Regex("^\\s*[-*+]\\s+.*")) -> "• " + t.replace(Regex("^\\s*[-*+]\\s+"), "")
            else -> t
        }.replace("**", "").replace("`", "")
    }.trim()

    private fun install() {
        if (!updater.canInstall()) {
            menu.show(
                btnInstall, "Allow app installs",
                message = "Android needs your permission before Quiet Home can hand an update to the installer.\n\n" +
                    "On the next screen, turn on “Allow from this source” (or “Install unknown apps”) for Quiet Home, then press Back. " +
                    "If the screen doesn't open, go to Settings › Apps › Security & restrictions › Unknown sources › Quiet Home.",
                items = listOf(
                    GlassMenu.Item("Open setting") {
                        if (!updater.openInstallPermission(this)) {
                            GlassMenu.chip(this, findViewById<FrameLayout>(R.id.root), "Open Settings › Apps › Security & restrictions on the TV")
                        }
                    },
                    GlassMenu.Item("Cancel") {},
                ),
            )
            // Remember that installing is blocked on the permission.
            updater.install()
            return
        }
        updater.install()
    }
}
