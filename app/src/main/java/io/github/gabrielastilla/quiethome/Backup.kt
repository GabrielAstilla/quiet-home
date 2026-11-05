package io.github.gabrielastilla.quiethome

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup and restore of everything personal: settings, pinned apps and order, wallpaper, per-app
 * backgrounds, custom artwork and imported fonts — one zip file.
 *
 * Used for (1) moving to the renamed app (a different Android package can't read the old one's data),
 * (2) moving to another TV, (3) keeping a copy before experiments. Runs on a background thread.
 */
object Backup {

    const val FILE_NAME = "quiethome-backup.zip"
    private const val PREFS_ENTRY = "prefs.json"
    private val PREF_FILES = listOf("quiet_home", "quiet_home_usage")
    private val DIRS = listOf("bg", "art", "fonts")

    class Contents(val pinned: Int, val images: Int, val fonts: Int)

    // ---- Export ----

    fun write(ctx: Context, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            val prefs = JSONObject()
            for (name in PREF_FILES) prefs.put(name, encode(ctx.getSharedPreferences(name, Context.MODE_PRIVATE)))
            prefs.put("_sourcePackage", ctx.packageName)
            prefs.put("_externalFiles", ctx.getExternalFilesDir(null)?.absolutePath ?: "")
            zip.putNextEntry(ZipEntry(PREFS_ENTRY))
            zip.write(prefs.toString(2).toByteArray())
            zip.closeEntry()
            val files = ctx.filesDir
            File(files, "wallpaper.jpg").takeIf { it.exists() }?.let { add(zip, it, "wallpaper.jpg") }
            for (d in DIRS) File(files, d).listFiles()?.filter { it.isFile }?.forEach { add(zip, it, "$d/${it.name}") }
        }
    }

    private fun add(zip: ZipOutputStream, f: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        f.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun encode(sp: SharedPreferences): JSONObject {
        val o = JSONObject()
        for ((k, v) in sp.all) {
            val t = when (v) {
                is Boolean -> "b"
                is Int -> "i"
                is Long -> "l"
                is Float -> "f"
                is String -> "s"
                else -> continue // string sets are not used
            }
            o.put(k, JSONObject().put("t", t).put("v", v))
        }
        return o
    }

    /** Exports to the app's own folder and (Android 10+) to Downloads/QuietHome. Returns the places written. */
    fun export(ctx: Context): List<String> {
        val written = ArrayList<String>()
        ctx.getExternalFilesDir("backup")?.let { dir ->
            val f = File(dir, FILE_NAME)
            f.outputStream().use { write(ctx, it) }
            written += f.absolutePath
        }
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val resolver = ctx.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/QuietHome")
                }
                // Replace this app's earlier export, if any.
                ownDownloads(ctx).forEach { resolver.delete(it, null, null) }
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                    resolver.openOutputStream(uri)?.use { write(ctx, it) }
                    written += "Download/QuietHome/$FILE_NAME"
                }
            }
        }
        return written
    }

    /** Backups this app itself saved to Downloads (Android only lists an app's own files there). */
    fun ownDownloads(ctx: Context): List<Uri> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val out = ArrayList<Uri>()
        runCatching {
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(FILE_NAME), null,
            )?.use { c ->
                while (c.moveToNext()) out += Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
            }
        }
        return out
    }

    // ---- Import ----

    /** Replaces settings and personal files with the backup's. Throws if the file isn't a Quiet Home backup. */
    fun restore(ctx: Context, input: InputStream): Contents {
        val tmp = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var prefsJson: JSONObject? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                // Only known names; never write outside the staging folder.
                val ok = name == PREFS_ENTRY || name == "wallpaper.jpg" ||
                    DIRS.any { name.startsWith("$it/") && !name.contains("..") && name.count { c -> c == '/' } == 1 }
                if (!ok || e.isDirectory) continue
                if (name == PREFS_ENTRY) prefsJson = JSONObject(zip.readBytes().decodeToString())
                else File(tmp, name).apply { parentFile?.mkdirs() }.outputStream().use { zip.copyTo(it) }
            }
        }
        val json = prefsJson ?: throw IllegalArgumentException("This file isn't a Quiet Home backup")
        val oldExternal = json.optString("_externalFiles")
        val newExternal = ctx.getExternalFilesDir(null)?.absolutePath ?: ""
        for (name in PREF_FILES) {
            val o = json.optJSONObject(name) ?: continue
            val e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for (k in o.keys()) {
                val item = o.getJSONObject(k)
                when (item.getString("t")) {
                    "b" -> e.putBoolean(k, item.getBoolean("v"))
                    "i" -> e.putInt(k, item.getInt("v"))
                    "l" -> e.putLong(k, item.getLong("v"))
                    "f" -> e.putFloat(k, item.getDouble("v").toFloat())
                    // Video paths point into the other app's folder; re-point them to this app's folder
                    // (the videos themselves must be copied there again — see README).
                    "s" -> e.putString(k, item.getString("v").let {
                        if (oldExternal.isNotEmpty() && newExternal.isNotEmpty()) it.replace(oldExternal, newExternal) else it
                    })
                }
            }
            e.commit()
        }
        val files = ctx.filesDir
        File(files, "wallpaper.jpg").delete()
        for (d in DIRS) File(files, d).listFiles()?.forEach { it.delete() }
        var images = 0
        var fonts = 0
        tmp.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(tmp).path
            f.copyTo(File(files, rel).apply { parentFile?.mkdirs() }, overwrite = true)
            if (rel.startsWith("fonts/")) fonts++ else images++
        }
        tmp.deleteRecursively()
        val pinned = ctx.getSharedPreferences("quiet_home", Context.MODE_PRIVATE).getString("pinned", "")
            ?.split('\n')?.count { it.isNotBlank() } ?: 0
        return Contents(pinned, images, fonts)
    }

    /** Applies a restore to the running app: caches, fonts, app list and every screen restyle. */
    fun applyRestored(app: LauncherApp) {
        app.clearBackgroundCache()
        app.repo.invalidateAllArtwork()
        Fonts.reloadAll(app)
        app.prefs.touch()
        app.repo.refresh()
    }

    // ---- Moving from another install of Quiet Home (same signing key) ----

    /**
     * Other installed copies of Quiet Home that can hand over their data: apps signed with the same key
     * as this one that expose a "<package>.backup" provider. Found by signature, not by name, so the
     * previous package ID doesn't have to be written into this app.
     */
    fun otherInstalls(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val homes = pm.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0,
        ).map { it.activityInfo.packageName }.distinct()
        return homes.filter { pkg ->
            pkg != ctx.packageName &&
                pm.checkSignatures(pkg, ctx.packageName) == PackageManager.SIGNATURE_MATCH &&
                pm.resolveContentProvider("$pkg.backup", 0) != null
        }
    }

    fun importFrom(ctx: Context, pkg: String): Contents {
        val uri = Uri.parse("content://$pkg.backup/$FILE_NAME")
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("The other app didn't respond")
        return input.use { restore(ctx, it) }
    }
}

/**
 * Hands a fresh backup to another install of Quiet Home signed with the same key (and nobody else).
 * Read-only; produced on demand in the cache folder.
 */
class BackupProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        if (mode != "r") throw SecurityException("read-only")
        val caller = Binder.getCallingUid()
        if (caller != Process.myUid() && ctx.packageManager.checkSignatures(caller, Process.myUid()) != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("Only Quiet Home installs signed with the same key can read this backup")
        }
        val f = File(ctx.cacheDir, "handover.zip")
        val id = Binder.clearCallingIdentity()
        try {
            f.outputStream().use { Backup.write(ctx, it) }
        } finally {
            Binder.restoreCallingIdentity(id)
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "application/zip"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
