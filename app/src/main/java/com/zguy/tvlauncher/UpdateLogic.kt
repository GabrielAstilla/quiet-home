package com.zguy.tvlauncher

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Pure update logic (no Android framework), so it is unit-tested on the JVM.
 *
 * Release structure expected on GitHub (see RELEASING.md):
 *   tag            v1.2.0            (any tag works; the version code comes from metadata, not the tag)
 *   assets         QuietHome.apk     the universal APK (same name every release)
 *                  QuietHome.json    {"packageName","versionCode","versionName","apk","sha256","minSdk"}
 */
object UpdateLogic {

    data class Asset(val name: String, val url: String, val size: Long, val contentType: String?)

    data class Release(
        val tag: String,
        val name: String,
        val notes: String,
        val draft: Boolean,
        val prerelease: Boolean,
        val publishedAt: String?,
        val assets: List<Asset>,
    )

    data class Metadata(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val apk: String,
        val sha256: String?,
        val minSdk: Int,
    )

    /** A release chosen as the update candidate. [metadataUrl] null means "no metadata; validate from the APK". */
    data class Candidate(val release: Release, val apk: Asset, val metadataUrl: String?)

    class InvalidRelease(message: String) : Exception(message)

    // ---- Parsing ----

    fun parseReleases(json: String): List<Release> {
        val arr = try {
            JSONArray(json)
        } catch (e: JSONException) {
            throw InvalidRelease("GitHub returned data that isn't a release list")
        }
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseRelease) }
    }

    private fun parseRelease(o: JSONObject): Release? {
        val tag = o.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
        val assetsJson = o.optJSONArray("assets") ?: JSONArray()
        val assets = (0 until assetsJson.length()).mapNotNull { j ->
            val a = assetsJson.optJSONObject(j) ?: return@mapNotNull null
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (name.isBlank() || url.isBlank()) null
            else Asset(name, url, a.optLong("size", -1), a.optString("content_type").takeIf { it.isNotBlank() })
        }
        return Release(
            tag = tag,
            name = o.optString("name").ifBlank { tag },
            notes = o.optString("body").trim(),
            draft = o.optBoolean("draft", false),
            prerelease = o.optBoolean("prerelease", false),
            publishedAt = o.optString("published_at").takeIf { it.isNotBlank() && it != "null" },
            assets = assets,
        )
    }

    fun parseMetadata(json: String, expectedPackage: String): Metadata {
        val o = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw InvalidRelease("Release metadata (QuietHome.json) is not valid JSON")
        }
        val pkg = o.optString("packageName")
        if (pkg != expectedPackage) throw InvalidRelease("Release metadata is for “$pkg”, not this app")
        val code = when (val v = o.opt("versionCode")) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        } ?: throw InvalidRelease("Release metadata has no valid versionCode")
        if (code <= 0) throw InvalidRelease("Release metadata has an invalid versionCode ($code)")
        val sha = o.optString("sha256").lowercase().takeIf { it.isNotBlank() }
        if (sha != null && !sha.matches(Regex("[0-9a-f]{64}"))) throw InvalidRelease("Release metadata has a malformed sha256")
        return Metadata(
            packageName = pkg,
            versionCode = code,
            versionName = o.optString("versionName").ifBlank { code.toString() },
            apk = o.optString("apk").ifBlank { "" },
            sha256 = sha,
            minSdk = o.optInt("minSdk", 0),
        )
    }

    // ---- Selection ----

    /**
     * Newest stable release (by published date, then API order) that has a usable APK.
     * Drafts and prereleases are skipped unless [includePrereleases].
     */
    fun selectCandidate(
        releases: List<Release>,
        apkName: String,
        metadataName: String,
        includePrereleases: Boolean = false,
    ): Candidate? {
        val eligible = releases
            .filter { !it.draft && (includePrereleases || !it.prerelease) }
            .sortedByDescending { it.publishedAt ?: "" } // ISO-8601 sorts chronologically
        for (r in eligible) {
            val apk = pickApk(r.assets, apkName) ?: continue
            val meta = r.assets.firstOrNull { it.name.equals(metadataName, ignoreCase = true) }
            return Candidate(r, apk, meta?.url)
        }
        return null
    }

    /** Exact asset name first; otherwise the single .apk if there is exactly one (never guesses between several). */
    fun pickApk(assets: List<Asset>, apkName: String): Asset? {
        assets.firstOrNull { it.name.equals(apkName, ignoreCase = true) }?.let { return it }
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        return apks.singleOrNull()
    }

    // ---- Versions ----

    /** Android version codes are integers; compare numerically (never compare version names as text). */
    fun isNewer(candidateCode: Long, installedCode: Long) = candidateCode > installedCode

    // ---- URLs ----

    fun releasesApiUrl(apiBase: String, repo: String) = "${apiBase.trimEnd('/')}/repos/$repo/releases?per_page=20"

    /** Stable public link that always serves the newest stable release's APK (used for Downloader). */
    fun latestDownloadUrl(repo: String, apkName: String) = "https://github.com/$repo/releases/latest/download/$apkName"

    fun isValidRepo(repo: String) = repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))

    // ---- Checksums ----

    fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
