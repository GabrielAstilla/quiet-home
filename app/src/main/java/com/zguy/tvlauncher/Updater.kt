package com.zguy.tvlauncher

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

/**
 * Checks GitHub Releases for a newer Quiet Home, downloads the APK, validates it and hands it to
 * Android's installer. All network and file work runs on one background thread; state changes are
 * delivered on the main thread. Nothing is downloaded or installed without the user choosing it.
 */
class Updater(private val app: LauncherApp) {

    sealed class State {
        object NotConfigured : State()
        object Idle : State()
        object Checking : State()
        data class UpToDate(val latestName: String?) : State()
        data class Available(val info: UpdateInfo) : State()
        data class Downloading(val info: UpdateInfo, val bytes: Long, val total: Long) : State()
        data class Ready(val info: UpdateInfo, val file: File, val versionName: String) : State()
        data class NeedsPermission(val info: UpdateInfo, val file: File, val versionName: String) : State()
        data class Installing(val info: UpdateInfo) : State()
        data class Error(val message: String, val canRetryDownload: Boolean) : State()
    }

    data class UpdateInfo(
        val candidate: UpdateLogic.Candidate,
        val metadata: UpdateLogic.Metadata?,
        /** Shown to the user; from metadata, else the release tag. */
        val versionName: String,
    ) {
        val notes get() = candidate.release.notes
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "qh-update").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val listeners = ArrayList<(State) -> Unit>()
    private val prefs = app.prefs

    @Volatile private var cancelRequested = false
    @Volatile private var activeConnection: HttpURLConnection? = null

    var state: State = if (configured) State.Idle else State.NotConfigured
        private set(value) {
            field = value
            listeners.toList().forEach { it(value) }
        }

    val repo: String
        get() = (if (BuildConfig.DEBUG) prefs.debugUpdateRepo.takeIf { it.isNotBlank() } else null) ?: BuildConfig.UPDATE_REPO

    private val apiBase: String
        get() = (if (BuildConfig.DEBUG) prefs.debugUpdateApi.takeIf { it.isNotBlank() } else null) ?: BuildConfig.UPDATE_API_BASE

    val configured get() = UpdateLogic.isValidRepo(repo)

    val installedVersionCode: Long get() = packageInfo(0).longCode()
    val installedVersionName: String get() = packageInfo(0).versionName ?: "?"

    fun addListener(l: (State) -> Unit) {
        listeners += l
        l(state)
    }

    fun removeListener(l: (State) -> Unit) {
        listeners -= l
    }

    private fun post(s: State) = main.post { state = s }

    // ---- Check ----

    /** [silent]: automatic daily check — on failure stay quiet (keep the previous state). */
    fun check(silent: Boolean = false) {
        if (!configured) {
            state = State.NotConfigured
            return
        }
        if (state is State.Checking || state is State.Downloading || state is State.Installing) return
        val previous = state
        if (!silent) state = State.Checking
        worker.execute {
            val result = try {
                doCheck()
            } catch (e: Exception) {
                State.Error(describe(e), canRetryDownload = false)
            }
            main.post {
                prefs.lastUpdateCheck = System.currentTimeMillis()
                state = if (silent && result is State.Error) previous else result
            }
        }
    }

    private fun doCheck(): State {
        val json = httpGetText(UpdateLogic.releasesApiUrl(apiBase, repo), api = true)
        val releases = UpdateLogic.parseReleases(json)
        val candidate = UpdateLogic.selectCandidate(releases, BuildConfig.UPDATE_APK_ASSET, BuildConfig.UPDATE_METADATA_ASSET)
            ?: return if (releases.none { !it.draft && !it.prerelease }) State.UpToDate(null)
            else throw UpdateLogic.InvalidRelease("The latest release has no ${BuildConfig.UPDATE_APK_ASSET}")
        val meta = candidate.metadataUrl?.let { UpdateLogic.parseMetadata(httpGetText(it, api = false), app.packageName) }
        val name = meta?.versionName ?: candidate.release.tag.removePrefix("v")
        val info = UpdateInfo(candidate, meta, name)
        if (meta != null && !UpdateLogic.isNewer(meta.versionCode, installedVersionCode)) return State.UpToDate(name)
        // Without metadata the version code is only known after downloading; validation then decides.
        return State.Available(info)
    }

    // ---- Download ----

    fun download() {
        val info = when (val s = state) {
            is State.Available -> s.info
            is State.Error -> lastInfo ?: return
            else -> return
        }
        lastInfo = info
        cancelRequested = false
        state = State.Downloading(info, 0, info.candidate.apk.size)
        worker.execute {
            val result = try {
                val file = doDownload(info)
                val v = validate(file, info)
                State.Ready(info, file, v)
            } catch (e: CancelledException) {
                cleanup()
                State.Available(info)
            } catch (e: Exception) {
                cleanup()
                State.Error(describe(e), canRetryDownload = true)
            }
            post(result)
        }
    }

    private var lastInfo: UpdateInfo? = null

    fun cancel() {
        cancelRequested = true
        activeConnection?.disconnect()
    }

    private class CancelledException : IOException("cancelled")

    private fun updatesDir() = File(app.cacheDir, "updates").apply { mkdirs() }

    private fun doDownload(info: UpdateInfo): File {
        val dir = updatesDir()
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, "QuietHome.apk.part")
        val conn = open(info.candidate.apk.url, api = false)
        activeConnection = conn
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpFailure(code, conn)
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.candidate.apk.size
            var done = 0L
            var lastPost = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelRequested) throw CancelledException()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastPost > 120) {
                            lastPost = now
                            post(State.Downloading(info, done, total))
                        }
                    }
                }
            }
            if (cancelRequested) throw CancelledException()
            if (total > 0 && done != total) throw IOException("Download incomplete ($done of $total bytes)")
        } catch (e: IOException) {
            if (cancelRequested) throw CancelledException()
            throw e
        } finally {
            activeConnection = null
            conn.disconnect()
        }
        val apk = File(dir, "QuietHome.apk")
        if (!part.renameTo(apk)) throw IOException("Couldn't save the download")
        return apk
    }

    // ---- Validation ----

    class Rejected(message: String) : Exception(message)

    /** Returns the APK's version name if it is safe to offer for install; throws [Rejected] otherwise. */
    private fun validate(file: File, info: UpdateInfo): String {
        val pm = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags)
            ?: throw Rejected("The downloaded file isn't a valid Android app (APK)")
        if (archive.packageName != app.packageName) {
            throw Rejected("The download is a different app (${archive.packageName}), not Quiet Home")
        }
        val code = archive.longCode()
        if (!UpdateLogic.isNewer(code, installedVersionCode)) {
            throw Rejected("The download (version code $code) isn't newer than the installed app ($installedVersionCode)")
        }
        info.metadata?.let { m ->
            if (m.versionCode != code) throw Rejected("Release metadata says version code ${m.versionCode} but the APK is $code")
            m.sha256?.let { expected ->
                val actual = sha256(file)
                if (actual != expected) throw Rejected("Checksum mismatch: the file is corrupted or not the published APK")
            }
        }
        val minSdk = archive.applicationInfo?.minSdkVersion ?: 0
        if (minSdk > Build.VERSION.SDK_INT) throw Rejected("This update needs Android API $minSdk; the TV has ${Build.VERSION.SDK_INT}")
        if (!sameSigner(archive)) {
            throw Rejected("The update is signed with a different key than the installed app, so Android would refuse it. " +
                "Only releases signed with the same key can update this install (see RELEASING.md).")
        }
        return archive.versionName ?: code.toString()
    }

    private fun signerDigests(info: PackageInfo, history: Boolean): Set<String> {
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return emptySet()
            when {
                si.hasMultipleSigners() -> si.apkContentsSigners
                history -> si.signingCertificateHistory
                else -> si.apkContentsSigners
            }
        } else {
            @Suppress("DEPRECATION") info.signatures
        } ?: return emptySet()
        return certs.map { UpdateLogic.sha256Hex(it.toByteArray()) }.toSet()
    }

    /** Same certificate(s), or the update's signing history contains the installed certificate (key rotation). */
    private fun sameSigner(archive: PackageInfo): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val installed = signerDigests(packageInfo(flags), history = false)
        if (installed.isEmpty()) return false
        val apkCurrent = signerDigests(archive, history = false)
        if (apkCurrent == installed) return true
        return signerDigests(archive, history = true).containsAll(installed)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- Install ----

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || app.packageManager.canRequestPackageInstalls()

    /** Opens Android's "Install unknown apps" screen for Quiet Home (several fallbacks for TV firmware). */
    fun openInstallPermission(activity: Activity): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
        for (i in intents) {
            try {
                activity.startActivity(i)
                return true
            } catch (e: Exception) {
            }
        }
        return false
    }

    /** Re-checks the permission after returning from Settings. */
    fun refreshPermission() {
        val s = state
        if (s is State.NeedsPermission && canInstall()) state = State.Ready(s.info, s.file, s.versionName)
    }

    /** Starts an install session; Android always shows its own confirmation screen. */
    fun install() {
        val (info, file, name) = when (val s = state) {
            is State.Ready -> Triple(s.info, s.file, s.versionName)
            is State.NeedsPermission -> Triple(s.info, s.file, s.versionName)
            else -> return
        }
        if (!file.exists()) {
            state = State.Error("The downloaded update was removed. Download it again.", canRetryDownload = true)
            lastInfo = info
            return
        }
        if (!canInstall()) {
            state = State.NeedsPermission(info, file, name)
            return
        }
        state = State.Installing(info)
        worker.execute {
            try {
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("QuietHome.apk", 0, file.length()).use { out ->
                        FileInputStream(file).use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(app, id, Intent(app, InstallResultReceiver::class.java), flags)
                    session.commit(pi.intentSender)
                }
            } catch (e: Exception) {
                post(State.Error("Couldn't start the installer: ${e.message ?: e.javaClass.simpleName}", canRetryDownload = false))
            }
        }
    }

    internal fun onInstallResult(status: Int, message: String?, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirm?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    app.startActivity(it)
                } catch (e: Exception) {
                    state = State.Error("Android's installer couldn't be opened", canRetryDownload = false)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                cleanup()
                state = State.UpToDate(installedVersionName)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // User pressed Cancel in the system dialog: keep the download so Install can be tried again.
                val r = (state as? State.Installing)?.info
                val f = File(updatesDir(), "QuietHome.apk")
                state = if (r != null && f.exists()) State.Ready(r, f, r.versionName) else State.Idle
            }
            else -> {
                cleanup()
                val reason = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "it conflicts with the installed app (usually a different signing key)"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "it isn't compatible with this TV"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "there isn't enough storage"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "installation was blocked by the system"
                    PackageInstaller.STATUS_FAILURE_INVALID -> "the APK is invalid"
                    else -> message ?: "unknown error"
                }
                state = State.Error("Android didn't install the update: $reason", canRetryDownload = true)
            }
        }
    }

    /** Deletes downloaded/partial APKs. Called after install, on failure, and at startup once installed. */
    fun cleanup() {
        File(app.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }

    /** At startup: remove leftovers (e.g. the APK of the update that is now installed). */
    fun startupCleanup() = worker.execute { cleanup() }

    // ---- Automatic daily check (opt-in; notifies only) ----

    fun maybeAutoCheck(onAvailable: (String) -> Unit) {
        if (!prefs.autoUpdateCheck || !configured) return
        val day = 24L * 60 * 60 * 1000
        if (System.currentTimeMillis() - prefs.lastUpdateCheck < day) return
        lateinit var l: (State) -> Unit
        l = { s ->
            if (s is State.Available) {
                onAvailable(s.info.versionName)
                main.post { removeListener(l) }
            } else if (s !is State.Checking && s !is State.Idle) main.post { removeListener(l) }
        }
        listeners += l
        check(silent = true)
    }

    // ---- HTTP ----

    private class HttpFailure(val code: Int, conn: HttpURLConnection) : IOException("HTTP $code") {
        val rateLimited = (code == 403 || code == 429) && conn.getHeaderField("X-RateLimit-Remaining") == "0"
        val resetAt: Long? = conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
    }

    private fun open(url: String, api: Boolean): HttpURLConnection {
        val u = URL(url)
        // Release builds only talk HTTPS. (Debug builds may use a local test server; see src/debug.)
        if (u.protocol != "https" && !BuildConfig.DEBUG) throw IOException("Refusing non-HTTPS URL")
        val c = u.openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true // GitHub asset links redirect (HTTPS → HTTPS) to its CDN
        c.setRequestProperty("User-Agent", "QuietHome/${installedVersionName} (Android TV)")
        if (api) {
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        } else {
            c.setRequestProperty("Accept", "application/octet-stream, application/json")
        }
        return c
    }

    private fun httpGetText(url: String, api: Boolean): String {
        val c = open(url, api)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw HttpFailure(code, c)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is Rejected -> e.message ?: "The update was rejected"
        is UpdateLogic.InvalidRelease -> e.message ?: "The release data is invalid"
        is HttpFailure -> when {
            e.rateLimited -> "GitHub's limit of 60 checks per hour for this network was reached." +
                (e.resetAt?.let { " Try again after ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it * 1000))}." } ?: " Try again later.")
            e.code == 404 -> "Repository or file not found. The TV can only check public repositories."
            e.code in 500..599 -> "GitHub is having problems (error ${e.code}). Try again later."
            else -> "GitHub returned error ${e.code}"
        }
        is UnknownHostException -> "No internet connection"
        is SocketTimeoutException -> "The connection timed out. Check the network and try again."
        is SSLException -> "A secure connection to GitHub couldn't be made (check the TV's date and time)"
        is IOException -> "Network error: ${e.message ?: "connection failed"}"
        else -> e.message ?: e.javaClass.simpleName
    }

    // ---- Helpers ----

    private fun packageInfo(flags: Int): PackageInfo = app.packageManager.getPackageInfo(app.packageName, flags)

    private fun PackageInfo.longCode(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()
}

/** Receives the system installer's result for our install session. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        @Suppress("DEPRECATION")
        val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_INTENT)
        context.launcher.updater.onInstallResult(status, message, confirm)
    }
}
