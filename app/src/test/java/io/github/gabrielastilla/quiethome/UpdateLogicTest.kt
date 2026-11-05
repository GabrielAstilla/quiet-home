package io.github.gabrielastilla.quiethome

import io.github.gabrielastilla.quiethome.UpdateLogic.Asset
import io.github.gabrielastilla.quiethome.UpdateLogic.InvalidRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdateLogicTest {

    private fun release(
        tag: String,
        published: String?,
        draft: Boolean = false,
        pre: Boolean = false,
        assets: String = """[{"name":"QuietHome.apk","browser_download_url":"https://x/$tag/QuietHome.apk","size":10},
                             {"name":"QuietHome.json","browser_download_url":"https://x/$tag/QuietHome.json","size":1}]""",
    ) = """{"tag_name":"$tag","name":"Quiet Home $tag","body":"Notes for $tag","draft":$draft,"prerelease":$pre,
            "published_at":${published?.let { "\"$it\"" } ?: "null"},"assets":$assets}"""

    private fun list(vararg r: String) = UpdateLogic.parseReleases("[" + r.joinToString(",") + "]")

    // ---- Version comparison ----

    @Test
    fun versionCodesCompareNumerically() {
        assertTrue(UpdateLogic.isNewer(10, 9))   // "10" < "9" as text — must still be newer
        assertTrue(UpdateLogic.isNewer(100, 99))
        assertFalse(UpdateLogic.isNewer(2, 2))
        assertFalse(UpdateLogic.isNewer(1, 2))
    }

    // ---- Stable release selection ----

    @Test
    fun skipsDraftsAndPrereleases() {
        val r = list(
            release("v1.3.0-beta", "2026-12-03T00:00:00Z", pre = true),
            release("v1.4.0", null, draft = true),
            release("v1.2.0", "2026-12-01T00:00:00Z"),
            release("v1.1.0", "2026-11-01T00:00:00Z"),
        )
        assertEquals("v1.2.0", UpdateLogic.selectCandidate(r, "QuietHome.apk", "QuietHome.json")!!.release.tag)
        assertEquals("v1.3.0-beta", UpdateLogic.selectCandidate(r, "QuietHome.apk", "QuietHome.json", includePrereleases = true)!!.release.tag)
    }

    @Test
    fun newestByDateNotByApiOrder() {
        val r = list(release("v1.1.0", "2026-11-01T00:00:00Z"), release("v1.2.0", "2026-12-01T00:00:00Z"))
        assertEquals("v1.2.0", UpdateLogic.selectCandidate(r, "QuietHome.apk", "QuietHome.json")!!.release.tag)
    }

    @Test
    fun skipsReleaseWithoutApkAndFallsBackToOlder() {
        val r = list(
            release("v1.2.0", "2026-12-01T00:00:00Z", assets = """[{"name":"notes.txt","browser_download_url":"https://x/n"}]"""),
            release("v1.1.0", "2026-11-01T00:00:00Z"),
        )
        assertEquals("v1.1.0", UpdateLogic.selectCandidate(r, "QuietHome.apk", "QuietHome.json")!!.release.tag)
    }

    @Test
    fun noUsableReleaseGivesNull() {
        assertNull(UpdateLogic.selectCandidate(list(), "QuietHome.apk", "QuietHome.json"))
        assertNull(UpdateLogic.selectCandidate(list(release("v1", "2026-01-01T00:00:00Z", assets = "[]")), "QuietHome.apk", "QuietHome.json"))
    }

    @Test
    fun metadataUrlIsOptional() {
        val r = list(release("v1.2.0", "2026-12-01T00:00:00Z",
            assets = """[{"name":"QuietHome.apk","browser_download_url":"https://x/a.apk"}]"""))
        val c = UpdateLogic.selectCandidate(r, "QuietHome.apk", "QuietHome.json")!!
        assertNull(c.metadataUrl)
    }

    // ---- APK asset choice ----

    @Test
    fun picksExactApkNameOrSingleApk() {
        val exact = listOf(Asset("Other.apk", "u1", 1, null), Asset("QuietHome.apk", "u2", 1, null))
        assertEquals("u2", UpdateLogic.pickApk(exact, "QuietHome.apk")!!.url)
        val single = listOf(Asset("quiet-home-1.2.apk", "u3", 1, null), Asset("README.md", "u4", 1, null))
        assertEquals("u3", UpdateLogic.pickApk(single, "QuietHome.apk")!!.url)
        val ambiguous = listOf(Asset("a.apk", "u5", 1, null), Asset("b.apk", "u6", 1, null))
        assertNull(UpdateLogic.pickApk(ambiguous, "QuietHome.apk"))
    }

    // ---- Invalid data ----

    @Test
    fun invalidReleaseListThrows() {
        try {
            UpdateLogic.parseReleases("""{"message":"Not Found"}""")
            fail("expected InvalidRelease")
        } catch (e: InvalidRelease) {
        }
    }

    @Test
    fun metadataValidation() {
        val ok = UpdateLogic.parseMetadata(
            """{"packageName":"io.github.gabrielastilla.quiethome","versionCode":12,"versionName":"1.4.0","apk":"QuietHome.apk","sha256":"${"a".repeat(64)}"}""",
            "io.github.gabrielastilla.quiethome",
        )
        assertEquals(12L, ok.versionCode)
        assertEquals("a".repeat(64), ok.sha256)
        val stringCode = UpdateLogic.parseMetadata("""{"packageName":"p","versionCode":"7"}""", "p")
        assertEquals(7L, stringCode.versionCode)

        val bad = listOf(
            "not json",
            """{"packageName":"other.app","versionCode":3}""",
            """{"packageName":"p"}""",
            """{"packageName":"p","versionCode":-1}""",
            """{"packageName":"p","versionCode":3,"sha256":"xyz"}""",
        )
        for (b in bad) {
            try {
                UpdateLogic.parseMetadata(b, "p")
                fail("expected InvalidRelease for $b")
            } catch (e: InvalidRelease) {
            }
        }
    }

    // ---- URLs and naming ----

    @Test
    fun urlConstruction() {
        assertEquals("https://api.github.com/repos/owner/quiet-home/releases?per_page=20",
            UpdateLogic.releasesApiUrl("https://api.github.com/", "owner/quiet-home"))
        assertEquals("https://github.com/owner/quiet-home/releases/latest/download/QuietHome.apk",
            UpdateLogic.latestDownloadUrl("owner/quiet-home", BuildConfig.UPDATE_APK_ASSET))
        assertEquals("QuietHome.apk", BuildConfig.UPDATE_APK_ASSET)
        assertEquals("QuietHome.json", BuildConfig.UPDATE_METADATA_ASSET)
        assertTrue(UpdateLogic.isValidRepo("gabz-z/quiet-home"))
        assertFalse(UpdateLogic.isValidRepo(""))
        assertFalse(UpdateLogic.isValidRepo("https://github.com/a/b"))
        assertFalse(UpdateLogic.isValidRepo("a/b/c"))
    }

    @Test
    fun sha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdateLogic.sha256Hex("abc".toByteArray()))
    }
}
