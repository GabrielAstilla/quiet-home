package com.zguy.tvlauncher

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.FrameLayout
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Invisible helper screen that picks an image with the remote and imports it:
 *   - wallpaper / per-app background → opens the preview editor before anything is saved
 *   - card artwork → centre-cropped to 16:9 and applied
 * Sources: the TV's Pictures library, the launcher's import folder (ADB-friendly), or a system file picker.
 */
class PickImageActivity : Activity() {

    private lateinit var app: LauncherApp
    private val main = Handler(Looper.getMainLooper())
    private var target = TARGET_WALLPAPER
    private var key: String? = null
    private var label: String = ""
    private lateinit var menu: GlassMenu
    /** True while waiting for a permission, file picker, query or import (screen must stay open). */
    private var waiting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = launcher
        target = intent.getIntExtra(EXTRA_TARGET, TARGET_WALLPAPER)
        key = intent.getStringExtra(EXTRA_KEY)
        label = intent.getStringExtra(EXTRA_LABEL) ?: ""
        val root = FrameLayout(this)
        val content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        menu = GlassMenu(this, root, content)
        if (savedInstanceState == null) root.post { chooseSource() }
    }

    private fun title() = when (target) {
        TARGET_ART -> "Artwork for $label"
        TARGET_APP_BG -> "Background for $label"
        else -> "Wallpaper"
    }

    private fun chooseSource() {
        val items = ArrayList<GlassMenu.Item>()
        if (target != TARGET_ART) items += GlassMenu.Item("Browse collection & imported files") {
            waiting = true
            WallpaperGalleryActivity.open(this, if (target == TARGET_APP_BG) key else null, label)
            finish()
        }
        items += next("From Pictures on this TV") { pickFromMediaStore() }
        items += next(if (target == TARGET_ART) "From import folder" else "From import folder (list)") { pickFromImportFolder() }
        val doc = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        if (doc.resolveActivity(packageManager) != null) {
            items += next("Browse files…") {
                try {
                    waiting = true
                    @Suppress("DEPRECATION")
                    startActivityForResult(doc, REQ_PICK)
                } catch (e: Exception) {
                    fail("File browser not available")
                }
            }
        }
        items += GlassMenu.Item("Cancel") {}
        showMenu(title(), items)
    }

    private fun next(label: String, action: () -> Unit) = GlassMenu.Item(label, action)

    /** The picker closes when its last menu closes, unless the chosen step is still working. */
    private fun showMenu(title: String, items: List<GlassMenu.Item>, message: String? = null) {
        waiting = false
        menu.show(null, title, items, message = message, onDismiss = {
            if (!menu.isShowing && !waiting) finish()
        })
    }

    private fun pickFromImportFolder() {
        val dir = getExternalFilesDir("wallpapers")
        val files = dir?.listFiles()?.filter(ImageOps::isImage)?.sortedBy { it.name.lowercase() }.orEmpty()
        if (files.isEmpty()) {
            showMenu(
                "Import folder is empty",
                listOf(GlassMenu.Item("OK") {}),
                "Copy a .jpg or .png here, then try again:\n\n${dir?.absolutePath}\n\nFrom a computer:\nadb push photo.jpg ${dir?.absolutePath}/",
            )
            return
        }
        listDialog(files.map { it.name }) { i -> import { FileInputStream(files[i]) } }
    }

    private fun imagePermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun pickFromMediaStore() {
        val perm = imagePermission()
        waiting = true
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), REQ_PERM)
            return
        }
        app.repo.worker.execute {
            val found = ArrayList<Pair<String, Uri>>()
            try {
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
                    null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC",
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    while (c.moveToNext() && found.size < 300) {
                        found += (c.getString(nameCol) ?: "Image") to
                            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idCol))
                    }
                }
            } catch (e: Exception) {
            }
            main.post {
                if (isDestroyed) return@post
                if (found.isEmpty()) {
                    showMenu(
                        "No pictures found",
                        listOf(GlassMenu.Item("OK") {}),
                        "This TV's Pictures library is empty. Use \"From import folder\" instead.",
                    )
                } else {
                    listDialog(found.map { it.first }) { i -> import { contentResolver.openInputStream(found[i].second) } }
                }
            }
        }
    }

    private fun listDialog(names: List<String>, onPick: (Int) -> Unit) {
        showMenu("Choose image", names.mapIndexed { i, n -> next(n) { onPick(i) } })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) pickFromMediaStore()
        else fail("Permission needed to read Pictures. You can use the import folder instead.")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && uri != null) import { contentResolver.openInputStream(uri) }
        else finish()
    }

    private fun import(open: () -> InputStream?) {
        waiting = true
        Toast.makeText(this, "Loading image…", Toast.LENGTH_SHORT).show()
        val k = key
        app.repo.worker.execute {
            val result: File? = try {
                if (target == TARGET_ART && k != null) {
                    val out = ImageFiles.appArtwork(this, k)
                    if (ImageOps.importCropped(open, out, 640, 360)) out else null
                } else {
                    val dm = resources.displayMetrics
                    val tmp = ImageFiles.temp(this)
                    if (ImageOps.importScaled(open, tmp, minOf(1920, maxOf(dm.widthPixels, 1280)), 1080)) tmp else null
                }
            } catch (e: Throwable) {
                android.util.Log.w("QuietHome", "Image import failed", e)
                null
            }
            main.post {
                if (result == null) {
                    fail("Couldn't read that image")
                } else if (target == TARGET_ART && k != null) {
                    app.repo.invalidateApp(k)
                    app.prefs.touch()
                    Toast.makeText(this, "Artwork updated", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    BackgroundEditorActivity.preview(this, result, if (target == TARGET_APP_BG) k else null)
                    finish()
                }
            }
        }
    }

    private fun fail(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val TARGET_WALLPAPER = 0
        const val TARGET_APP_BG = 1
        const val TARGET_ART = 2
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_LABEL = "label"
        private const val REQ_PICK = 1
        private const val REQ_PERM = 2

        fun start(from: Activity, target: Int, entry: AppEntry?) {
            GlassView.captureSnapshot(from, translucent = from is SettingsActivity)
            from.startActivity(
                Intent(from, PickImageActivity::class.java)
                    .putExtra(EXTRA_TARGET, target)
                    .putExtra(EXTRA_KEY, entry?.key)
                    .putExtra(EXTRA_LABEL, entry?.label)
            )
            @Suppress("DEPRECATION")
            from.overridePendingTransition(0, 0)
        }
    }
}
