package com.zguy.tvlauncher

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.TextClock
import android.widget.TextView
import java.io.File

/** Shared screensaver content: video (or wallpaper with a slow drift), clock and greeting overlay. */
class ScreensaverContent(private val root: View, private val videoOverride: File?) {

    private val ctx = root.context
    private val app = ctx.launcher
    private val prefs = app.prefs
    private val backdrop: BackdropView = root.findViewById(R.id.ss_backdrop)
    private val clockGreeting = ClockGreeting(ctx, root.findViewById<TextClock>(R.id.ss_clock), root.findViewById<TextView>(R.id.ss_greeting), forScreensaver = true)
    private var drift: ValueAnimator? = null

    init {
        backdrop.publishesGlass = false
        val sh = 0x80000000.toInt()
        root.findViewById<TextClock>(R.id.ss_clock).setShadowLayer(Ui.dpf(ctx, 18f), 0f, Ui.dpf(ctx, 2f), sh)
        root.findViewById<TextView>(R.id.ss_greeting).setShadowLayer(Ui.dpf(ctx, 10f), 0f, Ui.dpf(ctx, 1f), sh)
        Fonts.apply(root)
    }

    fun start() {
        // The screensaver shows the picture without the Home readability gradient tuned for text.
        val s = BgStyle.from(prefs).copy(overlayOpacity = (prefs.overlayOpacity * 0.5f).toInt())
        backdrop.applyStyle(s)
        val video = videoOverride ?: File(prefs.screensaverVideo).takeIf { prefs.screensaverSource == Prefs.SS_VIDEO && it.exists() }
        if (video != null && !prefs.lightweight) {
            backdrop.showVideoFile(app, video, s, Prefs.POS_CROP, animated = true, durationMs = 0)
        } else {
            backdrop.showGlobal(app, s, 0)
            val m = Motion.of(prefs)
            if (m.full && !backdrop.playingVideo) {
                // A very slow drift keeps still images alive without visible motion steps.
                drift = ObjectAnimator.ofFloat(backdrop, View.SCALE_X, 1f, 1.07f).apply {
                    duration = 45_000
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { backdrop.scaleY = backdrop.scaleX }
                    start()
                }
            }
        }
        clockGreeting.applyStyle()
        clockGreeting.start()
    }

    fun stop() {
        drift?.cancel()
        clockGreeting.stop()
        backdrop.stopVideo()
    }

    companion object {
        fun inflate(ctx: android.content.Context): View = LayoutInflater.from(ctx).inflate(R.layout.view_screensaver, null)
    }
}

/**
 * Launcher idle mode. Opened by Home/All Apps after the idle time, or as a preview.
 * Fades in; the first key press (any key) fades it out and is consumed, so the card selected
 * behind it is never launched by accident.
 */
class ScreensaverActivity : Activity() {

    private lateinit var content: ScreensaverContent
    private lateinit var view: View
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = ScreensaverContent.inflate(this)
        setContentView(view)
        window.setBackgroundDrawable(null)
        content = ScreensaverContent(view, intent.getStringExtra(EXTRA_VIDEO)?.let { File(it) })
        val m = Motion.of(launcher.prefs)
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(if (m.enabled) m.d(900) else 0).start()
    }

    override fun onStart() {
        super.onStart()
        content.start()
    }

    override fun onStop() {
        content.stop()
        super.onStop()
        if (!isFinishing) super.finish() // never linger behind an app (no fade while hidden)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Volume keys still work; everything else wakes and is swallowed (down AND up).
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) dismiss()
        return true
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        if (ev == null) return true
        // Ignore pointer hover (air-mouse remotes, emulator mouse) and small stick jitter;
        // a deliberate joystick push or scroll wakes it like a key press.
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_EXIT -> return true
        }
        if (ev.isFromSource(android.view.InputDevice.SOURCE_JOYSTICK)) {
            val x = ev.getAxisValue(MotionEvent.AXIS_X)
            val y = ev.getAxisValue(MotionEvent.AXIS_Y)
            if (kotlin.math.abs(x) < 0.5f && kotlin.math.abs(y) < 0.5f) return true
        }
        dismiss()
        return true
    }

    private fun dismiss() {
        if (closing) return
        closing = true
        val m = Motion.of(launcher.prefs)
        val d = m.d(260)
        val done = {
            super.finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        if (d <= 0) done() else view.animate().alpha(0f).setDuration(d).withEndAction(done).start()
    }

    override fun finish() = dismiss()

    companion object {
        private const val EXTRA_VIDEO = "video"

        fun start(from: Activity) {
            from.startActivity(Intent(from, ScreensaverActivity::class.java))
            @Suppress("DEPRECATION")
            from.overridePendingTransition(0, 0)
        }

        fun preview(from: Activity, video: File? = null) {
            from.startActivity(Intent(from, ScreensaverActivity::class.java).putExtra(EXTRA_VIDEO, video?.path))
            @Suppress("DEPRECATION")
            from.overridePendingTransition(0, 0)
        }
    }
}

/** Starts the screensaver after the idle time while a launcher screen is in front. */
class IdleTimer(private val activity: Activity, private val canStart: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private val task = Runnable {
        if (canStart()) ScreensaverActivity.start(activity) else reset()
    }

    fun reset() {
        main.removeCallbacks(task)
        val min = activity.launcher.prefs.screensaverIdleMin
        if (min > 0) main.postDelayed(task, min * 60_000L)
    }

    fun stop() = main.removeCallbacks(task)
}

/**
 * Optional system screensaver ("Dream"). Google TV normally only offers its own Ambient Mode;
 * on many builds this can be selected with ADB (see README). Not interactive: any key wakes the TV.
 */
class QuietDream : DreamService() {
    private var content: ScreensaverContent? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val v = ScreensaverContent.inflate(this)
        setContentView(v)
        content = ScreensaverContent(v, null)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        content?.start()
    }

    override fun onDreamingStopped() {
        content?.stop()
        super.onDreamingStopped()
    }
}
