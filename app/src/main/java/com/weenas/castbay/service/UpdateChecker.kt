package com.weenas.castbay.service

import android.content.Context
import com.weenas.castbay.util.Log
import com.weenas.castbay.util.Servers
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One version's release notes, in English and Chinese: plain-text bullet points. */
data class ReleaseNotes(val version: String, val date: String, val en: List<String>, val zh: List<String>)

/**
 * A newer CastBay: its version ("1.0.72"), its release page, and where to download its APK
 * ([apkUrls], tried in order) with the SHA-256 the download must have (null if not known),
 * and the latest versions' release notes, newest first.
 */
data class AppUpdate(
    val version: String,
    val url: String,
    val apkUrls: List<String> = emptyList(),
    val sha256: String? = null,
    val notes: List<ReleaseNotes> = emptyList()
) {
    /** The notes of the versions after [installed] up to this one, newest first. */
    fun notesSince(installed: String): List<ReleaseNotes> = notes.filter {
        UpdateChecker.isNewer(it.version, installed) && !UpdateChecker.isNewer(it.version, version)
    }

    internal fun toJson(): String = JSONObject()
        .put("version", version).put("page", url).put("urls", JSONArray(apkUrls)).put("sha256", sha256 ?: "")
        .put("notes", JSONArray(notes.map {
            JSONObject().put("version", it.version).put("date", it.date).put("en", JSONArray(it.en)).put("zh", JSONArray(it.zh))
        }))
        .toString()

    internal companion object {
        private fun strings(array: JSONArray?): List<String> =
            (0 until (array?.length() ?: 0)).mapNotNull { array!!.optString(it).takeIf(String::isNotBlank) }

        private fun releaseNotes(o: JSONObject): ReleaseNotes? {
            val version = o.optString("version").takeIf { it.isNotEmpty() } ?: return null
            return ReleaseNotes(version, o.optString("date"), strings(o.optJSONArray("en")), strings(o.optJSONArray("zh")))
        }

        /** From the website's latest.json, or as [toJson] kept it; null if malformed. */
        fun fromJson(json: String): AppUpdate? = runCatching {
            val o = JSONObject(json)
            val urls = o.optJSONArray("urls")
            AppUpdate(
                version = o.getString("version"),
                url = o.getString("page"),
                apkUrls = (0 until (urls?.length() ?: 0)).map { urls!!.getString(it) },
                sha256 = o.optString("sha256").takeIf { it.length == 64 },
                notes = o.optJSONArray("notes")?.let { array ->
                    (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::releaseNotes) }
                }.orEmpty()
            )
        }.getOrNull()
    }
}

/**
 * Looks for a newer release at most once a day: first on the website (castbay.weenas.com
 * publishes latest.json with each release), through its relay where the website can't be
 * reached, then on GitHub. The one request sends nothing about the TV (the server sees an address, as for
 * any web page). The result is kept, so the home screen can show it at once and without
 * asking again. Blocking; call off the main thread.
 */
class UpdateChecker(context: Context, private val appVersion: String, private val testVersions: () -> Boolean = { false }) {
    private val appContext = context.applicationContext
    private val preferences = context.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** The last release found, if newer than this app (no request made). */
    fun known(): AppUpdate? {
        val update = preferences.getString(KEY_UPDATE, null)?.let(AppUpdate::fromJson) ?: return null
        // A test version that was found while test versions were on is not offered once they are off.
        if (isTestVersion(update.version) && !testVersions()) return null
        return update.takeIf { isNewer(it.version, appVersion) }
    }

    /** Asks if the last check was over a day ago (or [force]); then as [known]. */
    fun check(nowMs: Long = System.currentTimeMillis(), force: Boolean = false): AppUpdate? {
        if (!force && nowMs - preferences.getLong(KEY_CHECKED_AT, 0) < CHECK_INTERVAL_MS) return known()
        fetch(nowMs)
        // Without an answer, it is tried again at the next start.
        return known()
    }

    /**
     * Asks now, whatever the time of the last check (About's Check for updates): a newer
     * release, null if this is the latest, or a failure if neither server answered.
     */
    fun checkNow(nowMs: Long = System.currentTimeMillis()): Result<AppUpdate?> =
        if (fetch(nowMs) != null) Result.success(known()) else Result.failure(java.io.IOException("no answer"))

    /** The latest release from the website, else GitHub, kept as [known]; null if neither answered. */
    private fun fetch(nowMs: Long): AppUpdate? {
        val latest = runCatching { fromWebsite() }
            .onFailure { Log.w(TAG, "Website update check failed: ${it.message}") }
            .getOrNull()
            ?: runCatching { fromGitHub() }
                .onFailure { Log.w(TAG, "GitHub update check failed: ${it.message}") }
                .getOrNull()
        // With test versions on, GitHub's list (pre-releases included) may hold something newer still.
        val test = if (testVersions()) {
            runCatching { newest(get(releasesUrl()), includeTests = true) }
                .onFailure { Log.w(TAG, "GitHub test version check failed: ${it.message}") }
                .getOrNull()
        } else null
        val best = if (test != null && isNewer(test.version, latest?.version ?: appVersion)) test else latest
        if (best != null) {
            preferences.edit().putString(KEY_UPDATE, best.toJson()).putLong(KEY_CHECKED_AT, nowMs).apply()
            Log.i(TAG, "Latest release ${best.version} (this is $appVersion)")
        }
        return best
    }

    // The website, or its relay (util/Servers.kt).
    private fun fromWebsite(): AppUpdate? = AppUpdate.fromJson(Servers.call(appContext, "/latest.json") { get(it.toString()) })

    private fun fromGitHub(): AppUpdate? = newest(get(releasesUrl()))

    /** GitHub's release list; a debug build can point it at a test server (preference debug_releases_url in "updates"). */
    private fun releasesUrl(): String =
        (if (com.weenas.castbay.BuildConfig.DEBUG) preferences.getString("debug_releases_url", null) else null) ?: RELEASES_URL

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            // GitHub's API requires a User-Agent.
            connection.setRequestProperty("User-Agent", "CastBay/$appVersion (https://github.com/weenas/castbay)")
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw Servers.HttpError(connection.responseCode)
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "CastBayUpdate"
        private const val RELEASES_URL = "https://api.github.com/repos/weenas/castbay/releases?per_page=10"
        private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private const val TIMEOUT_MS = 10_000
        private const val KEY_UPDATE = "latest"
        private const val KEY_CHECKED_AT = "checked_at"

        /**
         * The highest version among the published (not draft) releases in a GitHub listing,
         * with its CastBay-<version>.apk and that file's SHA-256 when GitHub lists them, and the
         * listed releases' notes (for when the website, which has them too, can't be reached).
         */
        internal fun newest(releasesJson: String, includeTests: Boolean = false): AppUpdate? {
            val releases = JSONArray(releasesJson)
            // Not drafts, and test versions (a pre-release, or a tag with a suffix, v1.8.7-beta.1) only for
            // a person who turned test versions on.
            val published = (0 until releases.length()).map { releases.getJSONObject(it) }
                .filter { !it.optBoolean("draft") }
                .filter { includeTests || (!it.optBoolean("prerelease") && '-' !in it.optString("tag_name")) }
            val notes = published.mapNotNull(::gitHubNotes).sortedWith { a, b -> compare(b.version, a.version) }
            return published
                .mapNotNull { release ->
                    val version = release.optString("tag_name").removePrefix("v")
                    if (parse(version) == null) return@mapNotNull null
                    val assets = release.optJSONArray("assets") ?: JSONArray()
                    val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                        .firstOrNull { it.optString("name") == "CastBay-$version.apk" }
                    AppUpdate(
                        version = version,
                        url = release.optString("html_url"),
                        apkUrls = listOfNotNull(apk?.optString("browser_download_url")?.takeIf { it.isNotEmpty() }),
                        sha256 = apk?.optString("digest")?.removePrefix("sha256:")?.takeIf { it.length == 64 },
                        notes = notes
                    )
                }
                .maxWithOrNull { a, b -> compare(a.version, b.version) }
        }

        /**
         * A GitHub release's notes: the bullet points under "## What's new" and "## 更新内容",
         * which the release workflow copies from the changelog, as plain text (as the website's
         * latest.json has them); null without either.
         */
        private fun gitHubNotes(release: JSONObject): ReleaseNotes? {
            val version = release.optString("tag_name").removePrefix("v")
            if (parse(version) == null) return null
            val en = mutableListOf<String>()
            val zh = mutableListOf<String>()
            var section: MutableList<String>? = null
            for (line in release.optString("body").lines()) {
                when {
                    line.startsWith("## ") -> section = when (line.removePrefix("## ").trim()) {
                        "What's new" -> en
                        "更新内容" -> zh
                        else -> null
                    }
                    line.startsWith("- ") -> section?.add(plain(line.removePrefix("- ")))
                }
            }
            if (en.isEmpty() && zh.isEmpty()) return null
            return ReleaseNotes(version, release.optString("published_at").take(10), en, zh)
        }

        /** Without Markdown's links, bold and code marks, as the website's latest.json. */
        private fun plain(markdown: String): String = markdown
            .replace(Regex("""\[([^\]]+)\]\([^)]*\)"""), "$1")
            .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
            .replace(Regex("`([^`]+)`"), "$1")
            .trim()

        /** Whether [candidate] ("1.0.72") is a later version than [current] ("1.0.70"). */
        fun isNewer(candidate: String, current: String): Boolean {
            if (parse(candidate) == null || parse(current) == null) return false
            return compare(candidate, current) > 0
        }

        // A test build's "1.1.0-dev+6228385" counts as 1.1.0.
        private fun parse(version: String): List<Int>? =
            version.substringBefore('-').substringBefore('+')
                .split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }

        /** "1.8.7-beta.1" is a test version; a "-dev+commit" build of this app counts as its release. */
        private fun preRelease(version: String): String? =
            version.substringBefore('+').substringAfter('-', "").takeIf { it.isNotEmpty() && !it.startsWith("dev") }

        fun isTestVersion(version: String) = preRelease(version) != null

        /** The 1.8.7-beta.1 < 1.8.7 order of semantic versioning: numbers first, then a test version before its release. */
        private fun compare(a: String, b: String): Int {
            val x = parse(a).orEmpty()
            val y = parse(b).orEmpty()
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            val pa = preRelease(a)
            val pb = preRelease(b)
            return when {
                pa == null && pb == null -> 0
                pa == null -> 1
                pb == null -> -1
                else -> comparePre(pa, pb)
            }
        }

        private fun comparePre(a: String, b: String): Int {
            val x = a.split('.')
            val y = b.split('.')
            for (i in 0 until minOf(x.size, y.size)) {
                val nx = x[i].toIntOrNull()
                val ny = y[i].toIntOrNull()
                val d = when {
                    nx != null && ny != null -> nx.compareTo(ny)
                    nx != null -> -1
                    ny != null -> 1
                    else -> x[i].compareTo(y[i])
                }
                if (d != 0) return d
            }
            return x.size.compareTo(y.size)
        }
    }
}
