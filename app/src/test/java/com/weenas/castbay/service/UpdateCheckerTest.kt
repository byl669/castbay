package com.weenas.castbay.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun comparesVersionsAsNumbers() {
        assertTrue(UpdateChecker.isNewer("1.0.72", "1.0.70"))
        assertTrue(UpdateChecker.isNewer("1.0.100", "1.0.99"))
        assertTrue(UpdateChecker.isNewer("1.1", "1.0.99"))
        assertFalse(UpdateChecker.isNewer("1.0.70", "1.0.70"))
        assertFalse(UpdateChecker.isNewer("1.0.57", "1.0.70"))
        assertFalse(UpdateChecker.isNewer("nightly", "1.0.70"))
    }

    @Test
    fun picksTheHighestPublishedRelease() {
        val json = """[
            {"tag_name": "v1.0.80", "draft": true, "html_url": "d"},
            {"tag_name": "v1.0.57", "html_url": "a"},
            {"tag_name": "v1.0.72", "html_url": "b", "assets": [
                {"name": "CastBay.apk", "browser_download_url": "x"},
                {"name": "CastBay-1.0.72.apk", "browser_download_url": "apk", "digest": "sha256:${"ab".repeat(32)}"}
            ]},
            {"tag_name": "test", "html_url": "c"}
        ]"""
        assertEquals(AppUpdate("1.0.72", "b", listOf("apk"), "ab".repeat(32)), UpdateChecker.newest(json))
        assertNull(UpdateChecker.newest("[]"))
    }

    @Test
    fun aTestBuildIsNeverOfferedAsAnUpdate() {
        // A pre-release for testers (also one whose tag has a suffix but no flag) counts as nothing, even
        // though its version, 1.8.7, is higher than the release the person has.
        val json = """[
            {"tag_name": "v1.8.7-beta.1", "prerelease": true, "html_url": "beta", "assets": [
                {"name": "CastBay-1.8.7-beta.1.apk", "browser_download_url": "betaapk"}
            ]},
            {"tag_name": "v1.8.8-rc.1", "html_url": "rc"},
            {"tag_name": "v1.8.6", "html_url": "release"}
        ]"""
        assertEquals("1.8.6", UpdateChecker.newest(json)!!.version)
        assertNull(UpdateChecker.newest("""[{"tag_name": "v1.8.7-beta.1", "prerelease": true, "html_url": "beta"}]"""))
    }

    @Test
    fun gitHubReleasesBringTheirNotes() {
        val body = "## What's new\\n\\n- Faster **start**, see [the guide](https://x).\\n- `CastBay.apk` only.\\n\\n" +
            "## 更新内容\\n\\n- 启动更快。\\n\\n## Verification\\n\\n- SHA-256: `ab`\\n\\n## What's Changed\\n* PR by @someone"
        val json = """[
            {"tag_name": "v1.3.3", "html_url": "b", "published_at": "2026-10-04T08:00:00Z", "body": "$body"},
            {"tag_name": "v1.3.2", "html_url": "a", "published_at": "2026-10-03T08:00:00Z", "body": "## Verification\\n\\n- SHA"}
        ]"""
        val update = UpdateChecker.newest(json)!!
        assertEquals(
            listOf(ReleaseNotes("1.3.3", "2026-10-04", listOf("Faster start, see the guide.", "CastBay.apk only."), listOf("启动更快。"))),
            update.notes
        )
        assertEquals(1, update.notesSince("1.3.0").size)
    }

    @Test
    fun readsTheWebsitesLatestJsonAndKeepsIt() {
        val json = """{"version": "1.0.86", "sha256": "${"cd".repeat(32)}", "urls": ["site", "github"], "page": "p"}"""
        val update = AppUpdate.fromJson(json)
        assertEquals(AppUpdate("1.0.86", "p", listOf("site", "github"), "cd".repeat(32)), update)
        assertEquals(update, AppUpdate.fromJson(update!!.toJson()))
        assertNull(AppUpdate.fromJson("not json"))
    }

    @Test
    fun releaseNotesComeWithTheUpdateAndAreKept() {
        val json = """{"version":"1.2.0","page":"p","urls":["u"],"sha256":"","notes":[
            {"version":"1.3.0","date":"2026-11-01","en":["Not out yet"],"zh":["未发布"]},
            {"version":"1.2.0","date":"2026-10-04","en":["New"],"zh":["新"]},
            {"version":"1.1.0","date":"2026-10-03","en":["Icon"],"zh":["图标"]},
            {"version":"1.0.102","date":"2026-10-03","en":["Android 6"],"zh":["安卓 6"]}]}"""
        val update = AppUpdate.fromJson(json)!!
        assertEquals(listOf("1.2.0", "1.1.0"), update.notesSince("1.0.102").map { it.version })
        assertEquals(listOf("新"), update.notes[1].zh)
        // Kept as the app stores it, notes included.
        assertEquals(update, AppUpdate.fromJson(update.toJson()))
    }

    @Test
    fun anUpdateWithoutNotesHasNone() {
        val update = AppUpdate.fromJson("""{"version":"1.2.0","page":"p","urls":[],"sha256":""}""")!!
        assertTrue(update.notesSince("1.1.0").isEmpty())
    }

    @Test
    fun aTestBuildComparesAsItsRelease() {
        assertTrue(UpdateChecker.isNewer("1.2.0", "1.1.0-dev+6228385"))
        assertFalse(UpdateChecker.isNewer("1.1.0", "1.1.0-dev+6228385"))
    }

    @Test
    fun aTestVersionComesBeforeItsReleaseAndAfterTheLastOne() {
        assertTrue(UpdateChecker.isNewer("1.8.7-beta.1", "1.8.6"))
        assertTrue(UpdateChecker.isNewer("1.8.7", "1.8.7-beta.1"))
        assertFalse(UpdateChecker.isNewer("1.8.7-beta.1", "1.8.7"))
        assertTrue(UpdateChecker.isNewer("1.8.7-beta.2", "1.8.7-beta.1"))
        assertTrue(UpdateChecker.isNewer("1.8.7-beta.10", "1.8.7-beta.9"))
        assertTrue(UpdateChecker.isNewer("1.8.7-rc.1", "1.8.7-beta.3"))
        assertFalse(UpdateChecker.isNewer("1.8.7-beta.1", "1.8.7-beta.1"))
        // The same test version again is not an update; a longer one after a shorter is.
        assertTrue(UpdateChecker.isNewer("1.8.7-beta.1.1", "1.8.7-beta.1"))
    }

    @Test
    fun aDevBuildStillCountsAsItsRelease() {
        assertFalse(UpdateChecker.isNewer("1.8.7", "1.8.7-dev+abc1234"))
        assertFalse(UpdateChecker.isNewer("1.8.6", "1.8.7-dev+abc1234"))
        assertTrue(UpdateChecker.isNewer("1.8.8", "1.8.7-dev+abc1234"))
        assertFalse(UpdateChecker.isTestVersion("1.8.7-dev+abc1234"))
        assertTrue(UpdateChecker.isTestVersion("1.8.7-beta.1"))
        assertFalse(UpdateChecker.isTestVersion("1.8.7"))
    }

    @Test
    fun withTestVersionsOnTheNewestTestVersionIsOffered() {
        val json = """[
            {"tag_name": "v1.8.7-beta.2", "prerelease": true, "html_url": "beta2", "assets": [
                {"name": "CastBay-1.8.7-beta.2.apk", "browser_download_url": "b2", "digest": "sha256:${"cd".repeat(32)}"}
            ]},
            {"tag_name": "v1.8.7-beta.1", "prerelease": true, "html_url": "beta1"},
            {"tag_name": "v1.8.6", "html_url": "release"}
        ]"""
        assertEquals("1.8.6", UpdateChecker.newest(json)!!.version)
        val test = UpdateChecker.newest(json, includeTests = true)!!
        assertEquals(AppUpdate("1.8.7-beta.2", "beta2", listOf("b2"), "cd".repeat(32)), test)
        // Once the release is out it wins over its test versions.
        val later = """[{"tag_name": "v1.8.7", "html_url": "release"}, {"tag_name": "v1.8.7-beta.2", "prerelease": true, "html_url": "beta2"}]"""
        assertEquals("1.8.7", UpdateChecker.newest(later, includeTests = true)!!.version)
    }
}
