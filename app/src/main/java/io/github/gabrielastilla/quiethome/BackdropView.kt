package io.github.gabrielastilla.quiethome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaPlayer
import android.util.AttributeSet
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.io.File
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/** Resolved background settings (from prefs, or a draft in the preview editor). */
data class BgStyle(
    val type: Int,
    val solid: Int,
    val gradTop: Int,
    val gradBottom: Int,
    val gradDir: Int,
    val wallpaperPosition: Int,
    val perApp: Int,
    val brightness: Int,
    val dim: Int,
    val blur: Int,
    val overlayColor: Int,
    val overlayOpacity: Int,
) {
    fun baseDrawable(): Drawable = when (type) {
        Prefs.BG_GRADIENT -> GradientDrawable(
            when (gradDir) {
                Prefs.DIR_VERTICAL -> GradientDrawable.Orientation.TOP_BOTTOM
                Prefs.DIR_HORIZONTAL -> GradientDrawable.Orientation.LEFT_RIGHT
                else -> GradientDrawable.Orientation.TL_BR
            },
            intArrayOf(gradTop, gradBottom),
        )
        else -> ColorDrawable(solid)
    }

    companion object {
        fun from(p: Prefs) = BgStyle(
            p.bgType, p.bgSolid, p.gradTop, p.gradBottom, p.gradDir, p.wallpaperPosition, p.perAppMode,
            p.brightness, p.dimPct, p.blur, p.overlayColor, p.overlayOpacity,
        )

        fun scaleType(position: Int) = when (position) {
            Prefs.POS_FILL -> ImageView.ScaleType.FIT_XY
            Prefs.POS_FIT -> ImageView.ScaleType.FIT_CENTER
            else -> ImageView.ScaleType.CENTER_CROP
        }
    }
}

/**
 * Full-screen background, bottom to top:
 *   base (solid / gradient) → image A/B (crossfading, brightness filter) → dim → colour overlay
 *   → readability gradient (darkens behind the title, top bar and cards so text is always legible).
 *
 * All layers are static views; a crossfade animates one alpha value, which is cheap on TV GPUs.
 */
class BackdropView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {

    private val base = View(ctx)
    private val imgA = ImageView(ctx)
    private val imgB = ImageView(ctx)
    /** Dimming and colour overlay combined into one layer to save a full-screen draw. */
    private val tint = View(ctx)

    private var front: ImageView? = null
    private var token: String? = null

    /** Home and All Apps publish their background to glass surfaces; the preview editor doesn't. */
    var publishesGlass = true
    private var style: BgStyle? = null
    private var frontBmp: Bitmap? = null
    private var frontScale = ImageView.ScaleType.CENTER_CROP
    private var glassSeq = 0
    private val glassTask = Runnable { composeGlass(0L) }
    private var requestSeq = 0

    /** Starts a new (possibly async) background request; older requests become stale. */
    fun beginRequest(): Int = ++requestSeq

    fun isCurrent(seq: Int) = seq == requestSeq

    // ---- Video wallpaper ----
    // A SurfaceView sits behind everything (hardware video overlay: no per-frame GPU copies, so
    // navigation stays smooth). A still frame covers it until the first frame renders, then fades away.
    private var surface: SurfaceView? = null
    private var player: MediaPlayer? = null
    private var videoFile: File? = null
    private var videoPosition = Prefs.POS_CROP
    private var videoRendering = false
    private var videoStill: Bitmap? = null
    private var videoW = 0
    private var videoH = 0
    private var videoWanted = true

    val playingVideo get() = videoFile != null

    /** Plays [file] silently and looped as the bottom layer; [still] shows until the first frame. */
    fun showVideo(file: File, still: Bitmap?, position: Int, durationMs: Long) {
        val same = videoFile?.path == file.path && player != null
        videoFile = file
        videoPosition = position
        videoStill = still
        base.visibility = View.INVISIBLE
        if (same && videoRendering) {
            layoutSurface()
            show("video:${file.path}", null, ImageView.ScaleType.CENTER_CROP, durationMs)
            return
        }
        show("still:${file.path}", still, BgStyle.scaleType(position), durationMs)
        if (videoWanted && !same) startPlayer()
    }

    fun stopVideo() {
        releasePlayer()
        surface?.let { removeView(it) }
        surface = null
        videoFile = null
        videoStill = null
        base.visibility = View.VISIBLE
    }

    /** Call from onStop: frees the decoder and leaves the still frame on screen. */
    fun pauseVideo() {
        videoWanted = false
        if (player == null) return
        releasePlayer()
        val f = videoFile ?: return
        videoStill?.let { show("still:${f.path}", it, BgStyle.scaleType(videoPosition), 0) }
    }

    /** Call from onStart. */
    fun resumeVideo() {
        videoWanted = true
        if (videoFile != null && player == null) startPlayer()
    }

    private fun startPlayer() {
        val f = videoFile ?: return
        val sv = surface ?: SurfaceView(context).also {
            surface = it
            addView(it, 0, LayoutParams(-1, -1, Gravity.CENTER))
        }
        videoRendering = false
        val mp = MediaPlayer()
        player = mp
        android.util.Log.i("QuietHome", "video: start ${f.name}")
        try {
            mp.setDataSource(f.path)
        } catch (e: Exception) {
            releasePlayer()
            return
        }
        mp.isLooping = true
        mp.setVolume(0f, 0f)
        mp.setOnVideoSizeChangedListener { _, w, h ->
            videoW = w
            videoH = h
            layoutSurface()
        }
        mp.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && player === mp) {
                videoRendering = true
                // Reveal the video only if the still is what's showing (not a per-app image).
                if (token?.startsWith("still:") == true) {
                    show("video:${f.path}", null, ImageView.ScaleType.CENTER_CROP, Motion.of(context.launcher.prefs).d(420))
                }
            }
            false
        }
        mp.setOnErrorListener { _, _, _ ->
            if (player === mp) releasePlayer() // the still frame stays: graceful fallback
            true
        }
        mp.setOnPreparedListener { if (player === it) it.start() }
        val holder = sv.holder
        val attach = {
            if (player === mp) {
                runCatching {
                    mp.setDisplay(holder)
                    mp.prepareAsync()
                }.onFailure { releasePlayer() }
            }
        }
        if (holder.surface?.isValid == true) attach()
        else holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                h.removeCallback(this)
                attach()
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                if (player === mp) releasePlayer()
            }
        })
    }

    private fun releasePlayer() {
        player?.let {
            android.util.Log.i("QuietHome", "video: released")
            runCatching { it.release() }
        }
        player = null
        videoRendering = false
    }

    /** Fill = stretch, Fit = whole frame, Crop = cover (the SurfaceView may exceed the screen; the window clips it). */
    private fun layoutSurface() {
        val sv = surface ?: return
        val pw = width
        val ph = height
        if (pw == 0 || ph == 0 || videoW == 0 || videoH == 0) return
        val (w, h) = when (videoPosition) {
            Prefs.POS_FILL -> pw to ph
            else -> {
                val k = if (videoPosition == Prefs.POS_FIT) minOf(pw / videoW.toFloat(), ph / videoH.toFloat())
                else maxOf(pw / videoW.toFloat(), ph / videoH.toFloat())
                (videoW * k).toInt() to (videoH * k).toInt()
            }
        }
        sv.layoutParams = LayoutParams(w, h, Gravity.CENTER)
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // One combined tint + readability layer (was two full-screen views): one less full-screen pass per frame.
        for (v in listOf(base, imgA, imgB, tint)) addView(v, LayoutParams(-1, -1))
        imgA.alpha = 0f
        imgB.alpha = 0f
        // Keep the tint layers above whichever image layer is raised during a crossfade.
        tint.translationZ = 2f
    }

    fun applyStyle(s: BgStyle) {
        style = s
        base.background = s.baseDrawable()
        val filter = if (s.brightness < 100) {
            val b = s.brightness / 100f
            ColorMatrixColorFilter(ColorMatrix().apply { setScale(b, b, b, 1f) })
        } else null
        imgA.colorFilter = filter
        imgB.colorFilter = filter
        val t = tintColor(s)
        val r = readability(s.overlayColor)
        tint.background = if (t == null) r else LayerDrawable(arrayOf(ColorDrawable(t), r))
        removeCallbacks(glassTask)
        postDelayed(glassTask, 40) // coalesce bursts of setting changes
    }

    private fun readability(c: Int): Drawable {
        val left = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Ui.withAlpha(c, 0.78f), Ui.withAlpha(c, 0.35f), Ui.withAlpha(c, 0f)),
        )
        val bottom = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Ui.withAlpha(c, 0.45f), Ui.withAlpha(c, 0f), Ui.withAlpha(c, 0.15f), Ui.withAlpha(c, 0.88f)),
        )
        return LayerDrawable(arrayOf(left, bottom))
    }

    /**
     * Shows [bmp] (or nothing, revealing the base) with a crossfade. Calling again mid-fade
     * retargets immediately: the newest request always wins, nothing is queued.
     */
    fun show(newToken: String?, bmp: Bitmap?, scale: ImageView.ScaleType, durationMs: Long) {
        if (newToken != null && newToken == token) {
            front?.scaleType = scale
            return
        }
        token = newToken
        val outgoing = front
        showBase(true) // visible during any change; hidden again once an opaque image covers the screen
        frontBmp = bmp
        frontScale = scale
        composeGlass(durationMs)
        if (bmp == null) {
            front = null
            if (outgoing != null) fadeTo(outgoing, 0f, durationMs) { outgoing.setImageDrawable(null) }
            return
        }
        val incoming = if (outgoing === imgA) imgB else imgA
        incoming.animate().cancel()
        incoming.scaleType = scale
        incoming.setImageBitmap(bmp)
        incoming.translationZ = 1f
        outgoing?.translationZ = 0.5f
        front = incoming
        if (durationMs <= 0L) {
            incoming.alpha = 1f
            outgoing?.alpha = 0f
            outgoing?.setImageDrawable(null)
            hideBaseIfCovered(bmp, scale)
            return
        }
        incoming.alpha = 0f
        fadeTo(incoming, 1f, durationMs) {
            if (front === incoming) {
                if (outgoing != null) {
                    outgoing.alpha = 0f
                    outgoing.setImageDrawable(null)
                }
                hideBaseIfCovered(bmp, scale)
            }
        }
    }

    /** The base colour/gradient is fully hidden behind an opaque image shown with Crop or Fill: skip drawing it. */
    private fun hideBaseIfCovered(bmp: Bitmap, scale: ImageView.ScaleType) {
        val covers = !bmp.hasAlpha() && (scale == ImageView.ScaleType.CENTER_CROP || scale == ImageView.ScaleType.FIT_XY)
        if (covers) showBase(false)
    }

    private fun showBase(on: Boolean) {
        if (playingVideo) return // video manages the base itself
        base.visibility = if (on) View.VISIBLE else View.INVISIBLE
    }

    private fun fadeTo(v: View, alpha: Float, d: Long, end: () -> Unit) {
        v.animate().cancel()
        if (d <= 0L) {
            v.alpha = alpha
            end()
        } else {
            v.animate().alpha(alpha).setDuration(d).setInterpolator(Motion.EASE_OUT).withEndAction(end).start()
        }
    }

    /**
     * Builds the small blurred picture glass surfaces sample: base, image (same position and
     * brightness), tint and readability gradient — i.e. what is actually on screen — at 192 px wide.
     */
    private fun composeGlass(durationMs: Long) {
        if (!publishesGlass) return
        val app = context.launcher
        val s = style ?: return
        val p = app.prefs
        if (p.lightweight || p.glassBlur == 0) {
            app.glassHub.publish(null, 0)
            return
        }
        val seq = ++glassSeq
        val img = frontBmp ?: (if (playingVideo) videoStill else null)
        val scale = frontScale
        val screenW = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val screenH = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val radius = GlassView.blurRadius(p.glassBlur)
        app.repo.worker.execute {
            val w = 192
            val h = (w * screenH / screenW.toFloat()).toInt().coerceAtLeast(1)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            s.baseDrawable().apply { setBounds(0, 0, w, h) }.draw(c)
            if (img != null && !img.isRecycled) {
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                if (s.brightness < 100) {
                    val b = s.brightness / 100f
                    paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(b, b, b, 1f) })
                }
                val m = Matrix()
                val sx = w / img.width.toFloat()
                val sy = h / img.height.toFloat()
                when (scale) {
                    ImageView.ScaleType.FIT_XY -> m.setScale(sx, sy)
                    ImageView.ScaleType.FIT_CENTER -> {
                        val k = minOf(sx, sy)
                        m.setScale(k, k)
                        m.postTranslate((w - img.width * k) / 2f, (h - img.height * k) / 2f)
                    }
                    else -> {
                        val k = maxOf(sx, sy)
                        m.setScale(k, k)
                        m.postTranslate((w - img.width * k) / 2f, (h - img.height * k) / 2f)
                    }
                }
                c.drawBitmap(img, m, paint)
            }
            tintColor(s, playingVideo)?.let { c.drawColor(it) }
            readability(s.overlayColor).apply { setBounds(0, 0, w, h) }.draw(c)
            ImageOps.boxBlur(out, radius)
            post { if (seq == glassSeq) app.glassHub.publish(out, durationMs) }
        }
    }

    /**
     * Black dim (d) under colour overlay (o) as one colour with alpha 1-(1-d)(1-o); null if none.
     * Video can't take a colour filter, so its brightness is folded into the dim.
     */
    private fun tintColor(s: BgStyle, video: Boolean = playingVideo): Int? {
        var d = s.dim / 100f
        if (video && s.brightness < 100) d = 1f - (1f - d) * (s.brightness / 100f)
        val o = s.overlayOpacity / 100f
        val a = 1f - (1f - d) * (1f - o)
        if (a <= 0.001f) return null
        val k = o / a
        val c = s.overlayColor
        return Color.argb((a * 255).toInt(), (Color.red(c) * k).toInt(), (Color.green(c) * k).toInt(), (Color.blue(c) * k).toInt())
    }

    /** The global background: wallpaper image when selected, otherwise just the base. */
    fun showGlobal(app: LauncherApp, s: BgStyle, durationMs: Long) {
        val wp = ImageFiles.wallpaper(app)
        val seq = beginRequest()
        if (s.type == Prefs.BG_VIDEO) {
            val vf = File(app.prefs.videoPath)
            if (app.exists(vf)) {
                val animated = app.prefs.animatedWallpaper && !app.prefs.lightweight
                showVideoFile(app, vf, s, s.wallpaperPosition, animated, durationMs, seq)
                return
            }
        }
        stopVideo()
        if (s.type == Prefs.BG_IMAGE && app.exists(wp)) {
            app.loadBackground(wp, s.blur) { t, bmp ->
                if (isCurrent(seq)) show(t, bmp, BgStyle.scaleType(s.wallpaperPosition), durationMs)
            }
        } else {
            show(null, null, ImageView.ScaleType.CENTER_CROP, durationMs)
        }
    }

    /** Shows a video (or just its still frame when [animated] is false). Missing/broken files fall back to the base. */
    fun showVideoFile(app: LauncherApp, vf: File, s: BgStyle, position: Int, animated: Boolean, durationMs: Long, seq: Int = beginRequest()) {
        app.repo.worker.execute {
            val still = runCatching { Video.ensureStill(app, vf) }.getOrNull()
            post {
                if (!isCurrent(seq)) return@post
                if (still == null) {
                    if (animated) showVideo(vf, null, position, durationMs) else { stopVideo(); show(null, null, ImageView.ScaleType.CENTER_CROP, durationMs) }
                    return@post
                }
                app.loadBackground(still, s.blur) { t, bmp ->
                    if (!isCurrent(seq)) return@loadBackground
                    if (animated) showVideo(vf, bmp, position, durationMs)
                    else {
                        stopVideo()
                        show(t, bmp, BgStyle.scaleType(position), durationMs)
                    }
                }
            }
        }
    }
}
