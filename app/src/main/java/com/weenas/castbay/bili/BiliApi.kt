package com.weenas.castbay.bili

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.weenas.castbay.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.TreeMap

/**
 * Fetches what to play for a cast, using the account the phone handed over.
 *
 * The phone sends `Play` with its `accessKey`, and the TV is expected to fetch the streams
 * itself — that is the whole point of the protocol: a DLNA renderer is given one 720p muxed
 * URL, while here the receiver asks for DASH and gets separate video and audio tracks at
 * whatever quality the account is entitled to.
 *
 * Two API shapes are used, both following the MIT-licensed Macast nirvana plugin:
 * `x/tv/playurl` (the TV endpoint, signed with Bilibili's app key) and the `durl` fallback
 * for sources that have no DASH at all (live rooms, some old videos).
 */
object BiliApi {
    private const val TAG = "CastBayBili"

    /** Bilibili's TV client key, as used by every open-source NVA receiver. */
    private const val APP_KEY = "4ebafd7c4951b366"
    private const val APP_SEC = "8cb98205e9b2ad3669aad0fce12a4c13"

    private const val PLAYURL = "https://api.bilibili.com/x/tv/playurl"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; CastBay) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private const val REFERER = "https://www.bilibili.com"

    /**
     * The quality asked for: the highest the API knows, letting it answer with what the account
     * actually allows (a free account gets 1080p on many videos, a 大会员 more). The phone's own
     * `userDesireQn` is 64 — 720p — because it still assumes a plain renderer.
     */
    const val WANTED_QN = 120

    /**
     * The highest quality where an AVC track exists. Bilibili's 4K and 8K are HEVC/AV1 only, and
     * AVC 1080p decodes on anything, which matters on a TV that may have an old decoder.
     */
    private const val AVC_MAX_QN = 80

    /** One DASH track: a single fragmented MP4, addressed by byte ranges. */
    data class Track(
        val id: Int,
        val url: String,
        val bandwidth: Long,
        val mimeType: String,
        val codecs: String,
        val width: Int,
        val height: Int,
        val frameRate: String,
        val initRange: String?,
        val indexRange: String?
    )

    /** A quality the account may switch to, for the phone's quality menu. */
    data class Quality(val qn: Int, val description: String, val needVip: Boolean, val needLogin: Boolean)

    /**
     * What to play. [single] is set when the API gave no DASH (a muxed `durl`): it is played as
     * one file, and [video] then describes it.
     */
    class Stream(
        val video: Track,
        val audio: Track?,
        val durationMs: Long,
        val quality: Int,
        val qualities: List<Quality>,
        val single: String? = null
    )

    /** A short, user-facing reason the cast cannot play. */
    class Failure(message: String) : Exception(message)

    /**
     * Asks for the streams of [aid]/[cid] (a video), or [epid] (an episode, `playurl_type=2`).
     * Blocking: call it off the main thread.
     */
    fun request(aid: Long, cid: Long, epid: Long, accessKey: String, qn: Int = WANTED_QN): Stream {
        val episodic = epid > 0
        val params = linkedMapOf(
            "access_key" to accessKey,
            "actionKey" to "appkey",
            "build" to "36700100",
            "cid" to cid.toString(),
            "fourk" to "1",
            // DASH: without this the API answers with a muxed 720p `durl`.
            "fnval" to "4048",
            "is_proj" to "1",
            "mobile_access_key" to accessKey,
            "mobi_app" to "android",
            "object_id" to (if (episodic) epid else aid).toString(),
            "ogv_aid" to aid.toString(),
            "platform" to "android",
            "playurl_type" to (if (episodic) "2" else "1"),
            "protocol" to "0",
            "qn" to qn.toString()
        )
        val body = get("$PLAYURL?${signed(params)}")
        val root = JsonParser.parseString(body).asJsonObject
        val code = root.int("code")
        if (code != 0) {
            val message = root.string("message").ifEmpty { "code $code" }
            throw Failure(message)
        }
        val data = root.objectOrNull("data") ?: throw Failure("no data")
        val durationMs = data.long("timelength")
        val quality = data.int("quality")
        val qualities = qualities(data)

        val dash = data.objectOrNull("dash")
        val videos = dash?.arrayOrNull("video") ?: emptyList()
        val audios = dash?.arrayOrNull("audio") ?: emptyList()
        val video = videos.maxByOrNull { track ->
            val trackQn = track.int("id")
            val avc = track.string("codecs").startsWith("avc")
            // AVC up to 1080p wins; anything else only if that is all there is.
            (if (avc && trackQn <= AVC_MAX_QN) 2 else if (avc) 1 else 0) * 1000 + trackQn
        }

        if (video == null) {
            // No DASH: a muxed file, one URL. Older codes on live rooms land here too.
            val durl = data.arrayOrNull("durl").orEmpty().firstOrNull()
            val url = durl?.string("url").orEmpty()
            if (url.isEmpty()) throw Failure("there is no playable stream for this video")
            Log.i(TAG, "Bilibili gave a muxed stream (no DASH): playing it as one file")
            return Stream(
                video = Track(quality, url, 0, "video/mp4", "", 0, 0, "", null, null),
                audio = null, durationMs = durationMs, quality = quality, qualities = qualities, single = url
            )
        }

        val picked = track(video)
        // 30280 is the 192 kbps track the TV apps use; anything else: the richest one.
        val audioJson = audios.firstOrNull { it.int("id") == 30280 }
            ?: audios.filter { it.string("codecs").startsWith("mp4a") }.maxByOrNull { it.long("bandwidth") }
        val audio = audioJson?.let(::track)
        Log.i(
            TAG,
            "Bilibili stream: ${picked.width}x${picked.height} ${picked.codecs} qn ${picked.id} · " +
                "audio ${audio?.id ?: "none"} · ${durationMs / 1000} s · account quality $quality"
        )
        return Stream(picked, audio, durationMs, quality, qualities)
    }

    private fun track(json: JsonObject): Track {
        val base = json.string("base_url").ifEmpty { json.string("baseUrl") }
        val segment = json.objectOrNull("segment_base") ?: json.objectOrNull("SegmentBase")
        return Track(
            id = json.int("id"),
            url = base,
            bandwidth = json.long("bandwidth"),
            mimeType = json.string("mime_type").ifEmpty { json.string("mimeType") }.ifEmpty { "video/mp4" },
            codecs = json.string("codecs"),
            width = json.int("width"),
            height = json.int("height"),
            frameRate = json.string("frame_rate").ifEmpty { json.string("frameRate") },
            initRange = segment?.string("initialization") ?: segment?.string("Initialization"),
            indexRange = segment?.string("index_range") ?: segment?.string("indexRange")
        )
    }

    private fun qualities(data: JsonObject): List<Quality> {
        val byQn = LinkedHashMap<Int, Quality>()
        data.arrayOrNull("support_formats").orEmpty().forEach { format ->
            val qn = format.int("quality")
            if (qn > 0) {
                byQn[qn] = Quality(
                    qn, format.string("new_description").ifEmpty { format.string("display_desc") }, false, false
                )
            }
        }
        data.arrayOrNull("qn_extras").orEmpty().forEach { extra ->
            val qn = extra.int("qn")
            val known = byQn[qn]
            byQn[qn] = Quality(
                qn,
                known?.description.orEmpty(),
                extra.bool("need_vip"),
                extra.bool("need_login")
            )
        }
        return byQn.values.sortedByDescending { it.qn }
    }

    /**
     * One MPD for one video and one audio track, in the on-demand profile: each track is a
     * single file with an `sidx`, so the player needs only the byte range of the index and the
     * range of the initialization header. Serving it ourselves keeps the phone's own network
     * out of it, and lets the player see the whole timeline (duration, seeking) up front.
     */
    fun buildMpd(stream: Stream): String {
        val duration = if (stream.durationMs > 0) {
            "PT%.3fS".format(java.util.Locale.US, stream.durationMs / 1000.0)
        } else {
            null
        }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" ")
            append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" type=\"static\" ")
            append("minBufferTime=\"PT1.5S\"")
            if (duration != null) append(" mediaPresentationDuration=\"").append(duration).append("\"")
            append(">\n")
            append("  <Period start=\"PT0S\"")
            if (duration != null) append(" duration=\"").append(duration).append("\"")
            append(">\n")
            appendRepresentation("video", stream.video, stream.video.mimeType.ifEmpty { "video/mp4" })
            stream.audio?.let { appendRepresentation("audio", it, it.mimeType.ifEmpty { "audio/mp4" }) }
            append("  </Period>\n")
            append("</MPD>\n")
        }
    }

    private fun StringBuilder.appendRepresentation(kind: String, track: Track, mimeType: String) {
        append("    <AdaptationSet contentType=\"").append(kind).append("\" mimeType=\"")
            .append(escape(mimeType)).append("\" segmentAlignment=\"true\" startWithSAP=\"1\">\n")
        append("      <Representation id=\"").append(kind).append("\"")
        if (track.bandwidth > 0) append(" bandwidth=\"").append(track.bandwidth).append("\"")
        if (track.codecs.isNotEmpty()) append(" codecs=\"").append(escape(track.codecs)).append("\"")
        if (track.width > 0 && track.height > 0) {
            append(" width=\"").append(track.width).append("\" height=\"").append(track.height).append("\"")
        }
        if (track.frameRate.isNotEmpty() && track.frameRate.matches(Regex("^\\d+(/\\d+)?(\\.\\d+)?$"))) {
            append(" frameRate=\"").append(track.frameRate).append("\"")
        }
        append(">\n")
        append("        <BaseURL>").append(escape(track.url, attribute = false)).append("</BaseURL>\n")
        if (track.indexRange != null) {
            append("        <SegmentBase indexRange=\"").append(escape(track.indexRange)).append("\">\n")
            track.initRange?.let {
                append("          <Initialization range=\"").append(escape(it)).append("\" />\n")
            }
            append("        </SegmentBase>\n")
        }
        append("      </Representation>\n")
        append("    </AdaptationSet>\n")
    }

    private fun escape(value: String, attribute: Boolean = true): String = buildString(value.length + 16) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> if (attribute) append("&quot;") else append(ch)
                '\'' -> if (attribute) append("&apos;") else append(ch)
                else -> append(ch)
            }
        }
    }

    // ---- request ---------------------------------------------------------------------------

    /** The app-key signature the TV endpoint requires: MD5 of the sorted query plus the secret. */
    private fun signed(params: Map<String, String>): String {
        val all = TreeMap(params)
        all["appkey"] = APP_KEY
        all["ts"] = (System.currentTimeMillis() / 1000).toString()
        val query = all.entries.joinToString("&") { "${it.key}=${encode(it.value)}" }
        return "$query&sign=${md5(query + APP_SEC)}"
    }

    private fun encode(value: String): String = try {
        URLEncoder.encode(value, "UTF-8")
    } catch (error: Exception) {
        value
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Referer", REFERER)
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            if (stream == null) throw Failure("HTTP $code") else stream.use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                input.copyTo(bytes)
                String(bytes.toByteArray(), Charsets.UTF_8)
            }
        } catch (error: Failure) {
            throw error
        } catch (error: Exception) {
            throw Failure(error.message ?: "the network did not answer")
        } finally {
            connection.disconnect()
        }
    }

    // ---- JSON helpers (the API omits fields freely) -----------------------------------------

    private fun JsonObject.int(name: String): Int = runCatching { get(name)?.asInt ?: 0 }.getOrDefault(0)

    private fun JsonObject.long(name: String): Long = runCatching { get(name)?.asLong ?: 0L }.getOrDefault(0L)

    private fun JsonObject.string(name: String): String =
        runCatching { get(name)?.takeIf { !it.isJsonNull }?.asString.orEmpty() }.getOrDefault("")

    private fun JsonObject.bool(name: String): Boolean =
        runCatching { get(name)?.takeIf { !it.isJsonNull }?.asBoolean ?: false }.getOrDefault(false)

    private fun JsonObject.objectOrNull(name: String): JsonObject? =
        runCatching { get(name)?.takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()

    private fun JsonObject.arrayOrNull(name: String): List<JsonObject>? =
        runCatching {
            get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
        }.getOrNull()

    private const val TIMEOUT_MS = 10_000
}
