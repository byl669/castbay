package com.weenas.castbay.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.weenas.castbay.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.util.EventLogger
import com.weenas.castbay.BuildConfig
import com.weenas.castbay.R
import com.weenas.castbay.protocol.AirPlayNative

/**
 * Plays AirPlay video (HLS) with ExoPlayer.
 *
 * The protocol core rewrites the sender's playlists and serves them on a local URL, so this
 * only needs to play that URL and report progress. Commands arrive on protocol threads and
 * are posted to the main thread, where ExoPlayer lives; [playbackInfo] is answered from a
 * snapshot so the sender's once-a-second poll never waits on the main thread.
 */
class HlsPlayer(
    context: Context,
    /**
     * Called on the main thread when playback ends on its own (null) or fails (a short,
     * user-facing reason).
     */
    private val onFinished: (error: String?) -> Unit
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** Main thread only. */
    var player: ExoPlayer? = null
        private set

    @Volatile private var snapshot = Snapshot()
    /** Linear gain from the sender's volume slider; applied to each new player. */
    @Volatile private var volume = 1f

    private data class Snapshot(
        val durationSec: Double = 0.0,
        val positionSec: Double = 0.0,
        val rate: Double = 0.0,
        val state: Double = AirPlayNative.PLAYBACK_NOT_STARTED,
        val buffering: Boolean = true
    )

    // For the stats overlay (main thread).
    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    private var bandwidthBps = 0L

    private val statsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime, decoderName: String,
            initializedTimestampMs: Long, initializationDurationMs: Long
        ) {
            videoDecoder = decoderName
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime, decoderName: String,
            initializedTimestampMs: Long, initializationDurationMs: Long
        ) {
            audioDecoder = decoderName
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime, format: Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
        ) {
            // Adaptive streams change quality as the network allows; this shows how high it went.
            val mbps = if (format.bitrate > 0) "%.1f Mbps".format(java.util.Locale.US, format.bitrate / 1e6) else "?"
            Log.i(TAG, "Video quality: ${format.width}x${format.height} · $mbps · ${format.codecs ?: format.sampleMimeType}")
        }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long
        ) {
            bandwidthBps = bitrateEstimate
        }
    }

    /** What is playing, for the stats overlay. Main thread only; null when idle. */
    fun stats(source: String = "AirPlay video"): PlaybackStats? {
        val exo = player ?: return null
        val video = exo.videoFormat?.let { format ->
            VideoStats(
                codec = StatsFormat.codecName(format.codecs ?: format.sampleMimeType),
                width = format.width,
                height = format.height,
                fps = format.frameRate.takeIf { it > 0 }?.toDouble(),
                bitrateBps = format.bitrate.takeIf { it != Format.NO_VALUE }?.toLong(),
                decoder = videoDecoder,
                droppedFrames = exo.videoDecoderCounters?.droppedBufferCount?.toLong() ?: 0
            )
        }
        val audio = exo.audioFormat?.let { format ->
            AudioStats(
                codec = StatsFormat.codecName(format.codecs ?: format.sampleMimeType),
                sampleRate = format.sampleRate.takeIf { it != Format.NO_VALUE } ?: 0,
                channels = format.channelCount.takeIf { it != Format.NO_VALUE } ?: 0,
                bitrateBps = format.bitrate.takeIf { it != Format.NO_VALUE }?.toLong(),
                decoder = audioDecoder
            )
        }
        return PlaybackStats(
            source = source,
            video = video,
            audio = audio,
            extra = listOf(
                "Network" to StatsFormat.bitrate(bandwidthBps.takeIf { it > 0 }),
                "Buffer" to "%.1f s".format(java.util.Locale.US, exo.totalBufferedDuration / 1000.0)
            )
        )
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateSnapshot()
            if (player != null) main.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            updateSnapshot()
            if (state == Player.STATE_ENDED) {
                Log.i(TAG, "Playback ended")
                finish(null)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = updateSnapshot()

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback failed (${error.errorCodeName})", error)
            com.weenas.castbay.util.UsageStats.failure("video")
            finish(describe(error))
        }
    }

    /**
     * [onStarted] runs on the main thread once [player] exists, so the UI can attach it.
     * [mimeType] is the sender's, where it says (DLNA): it tells HLS and DASH links apart.
     * [subtitles] (DLNA) are fetched first, so the video starts with them.
     */
    fun play(
        url: String,
        startPositionSec: Float,
        mimeType: String? = null,
        subtitles: List<com.weenas.castbay.dlna.DlnaSubtitle> = emptyList(),
        onStarted: () -> Unit
    ) {
        // Mark active right away: the sender polls playback info while the post is pending.
        snapshot = Snapshot(positionSec = startPositionSec.toDouble(), state = AirPlayNative.PLAYBACK_ACTIVE)
        val start = starts.incrementAndGet()
        if (subtitles.isEmpty()) {
            main.post { startOnMain(start, url, startPositionSec, mimeType, emptyList(), onStarted) }
        } else {
            kotlin.concurrent.thread(name = "CastBay-subtitles") {
                val tracks = subtitles.mapNotNull { SubtitleFiles.load(it.url, it.format) }
                main.post { startOnMain(start, url, startPositionSec, mimeType, tracks, onStarted) }
            }
        }
    }

    /** Counts play() and stop() calls: a start whose subtitles took a while yields to a later one. */
    private val starts = java.util.concurrent.atomic.AtomicInteger()

    private fun startOnMain(
        start: Int,
        url: String,
        startPositionSec: Float,
        mimeType: String?,
        subtitles: List<MediaItem.SubtitleConfiguration>,
        onStarted: () -> Unit
    ) {
        if (start != starts.get()) return
        Log.i(TAG, "Playing ${Log.personal(url)} from ${startPositionSec}s")
        // A stop() queued just before this play() has reset the snapshot.
        snapshot = Snapshot(positionSec = startPositionSec.toDouble(), state = AirPlayNative.PLAYBACK_ACTIVE)
        val exo = player ?: ExoPlayer.Builder(appContext)
            .setTrackSelector(DefaultTrackSelector(appContext, AdaptiveTrackSelection.Factory(
                QUALITY_INCREASE_AFTER_MS, 25_000, 25_000, 0.7f
            )))
            .build().also {
            it.addListener(listener)
            it.addAnalyticsListener(statsListener)
            // States, selected formats, segment loads and errors, tagged "EventLogger".
            if (BuildConfig.DEBUG) it.addAnalyticsListener(EventLogger())
            it.volume = volume
            player = it
        }
        // Usually the core's local .m3u8 (HLS), but senders may also pass a plain http(s)
        // media URL, so let ExoPlayer infer the format from the URL, or from the sender's MIME
        // type where it gives one: a DLNA app's HLS or DASH link may have no .m3u8 or .mpd.
        val item = MediaItem.Builder().setUri(url).setMimeType(adaptiveMimeType(mimeType))
            .setSubtitleConfigurations(subtitles).build()
        try {
            exo.setMediaItem(item, (startPositionSec * 1000).toLong().coerceAtLeast(0))
            exo.prepare()
        } catch (error: RuntimeException) {
            // E.g. a format no media source handles (IllegalStateException): it fails like a
            // playback error, telling the sender, rather than crashing the app.
            Log.e(TAG, "Couldn't start playback", error)
            com.weenas.castbay.util.UsageStats.failure("video")
            onStarted()
            finish(appContext.getString(R.string.error_video_format))
            return
        }
        exo.playWhenReady = true
        main.removeCallbacks(progressUpdater)
        main.post(progressUpdater)
        onStarted()
    }

    fun seek(positionSec: Float) {
        main.post {
            player?.seekTo((positionSec * 1000).toLong().coerceAtLeast(0))
            updateSnapshot()
        }
    }

    fun setRate(rate: Float) {
        main.post {
            player?.playWhenReady = rate > 0f
            updateSnapshot()
        }
    }

    /**
     * TV-remote controls. The sender learns about them from its next playback-info poll,
     * so its own controls stay in sync.
     */
    fun togglePause() {
        main.post {
            player?.let { it.playWhenReady = !it.playWhenReady }
            updateSnapshot()
        }
    }

    fun seekBy(deltaSec: Int) {
        main.post {
            val exo = player ?: return@post
            val target = (exo.currentPosition + deltaSec * 1000L).coerceAtLeast(0)
            val duration = exo.duration
            exo.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
            updateSnapshot()
        }
    }

    fun setVolume(gain: Float) {
        volume = gain
        main.post { player?.volume = gain }
    }

    fun stop() {
        starts.incrementAndGet()
        main.post { release() }
    }

    /** Drops a finished video's "finished", once told or when a new sender connects. */
    fun forgetFinished() {
        if (snapshot.state == AirPlayNative.PLAYBACK_FINISHED) snapshot = Snapshot()
    }

    /** Where playback is, for senders that poll (DLNA). Thread-safe. */
    data class Progress(
        val positionSec: Double,
        val durationSec: Double,
        /** Loading or playing a video (not stopped, finished or failed). */
        val active: Boolean,
        val playing: Boolean,
        val buffering: Boolean,
        val finished: Boolean
    )

    fun progress(): Progress = snapshot.let {
        Progress(
            positionSec = it.positionSec,
            durationSec = it.durationSec,
            active = it.state == AirPlayNative.PLAYBACK_ACTIVE,
            playing = it.rate > 0,
            buffering = it.buffering,
            finished = it.state == AirPlayNative.PLAYBACK_FINISHED
        )
    }

    /**
     * Thread-safe; see [com.weenas.castbay.protocol.VideoPlaybackListener.playbackInfo].
     * "Finished" is told once: the sender ends its session on seeing it. Kept, it answered the
     * next video's first poll, which comes before that video's playlist has even arrived, and
     * the sender gave up: YouTube failed to cast on the Sony after an earlier video.
     */
    fun playbackInfo(): DoubleArray = snapshot.also {
        if (it.state == AirPlayNative.PLAYBACK_FINISHED) forgetFinished()
    }.let {
        doubleArrayOf(
            it.durationSec,
            it.positionSec,
            it.rate,
            it.state,
            if (it.buffering) 1.0 else 0.0,
            if (it.buffering) 0.0 else 1.0
        )
    }

    private fun finish(error: String?) {
        release()
        // Tells the sender the video is over, so it ends the session.
        snapshot = Snapshot(state = AirPlayNative.PLAYBACK_FINISHED)
        onFinished(error)
    }

    private fun describe(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            // The TV fetches the video itself, so a site the phone reaches only through a
            // proxy or VPN is unreachable here (e.g. googlevideo.com for YouTube).
            appContext.getString(R.string.error_video_unreachable)
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
            appContext.getString(R.string.error_video_refused)
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            appContext.getString(R.string.error_video_format)
        else -> appContext.getString(R.string.error_video_failed, error.errorCodeName)
    }

    private fun release() {
        main.removeCallbacks(progressUpdater)
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        videoDecoder = null
        audioDecoder = null
        bandwidthBps = 0
        // Keep "finished" for the sender's next poll (see playbackInfo).
        if (snapshot.state != AirPlayNative.PLAYBACK_FINISHED) snapshot = Snapshot()
    }

    private fun updateSnapshot() {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET }?.div(1000.0) ?: 0.0
        snapshot = Snapshot(
            durationSec = duration,
            positionSec = exo.currentPosition / 1000.0,
            rate = if (exo.isPlaying) 1.0 else 0.0,
            state = if (exo.playbackState == Player.STATE_ENDED) {
                AirPlayNative.PLAYBACK_FINISHED
            } else {
                AirPlayNative.PLAYBACK_ACTIVE
            },
            buffering = exo.playbackState == Player.STATE_BUFFERING
        )
    }

    private companion object {
        const val TAG = "CastBayHls"
        /**
         * Buffered before moving up a quality (ExoPlayer's default is 10 s), so a video climbs
         * sooner to what the network allows (YouTube offers up to 4K). Starting higher instead
         * was worse: on the Sony, 1440p took 6.9 s to start, not 1.5–3.5 s, and fell back.
         */
        const val QUALITY_INCREASE_AFTER_MS = 5_000
        const val PROGRESS_INTERVAL_MS = 250L
    }
}

/** Media3's MIME type for an HLS or DASH [mimeType] (any case); null otherwise, to infer the format from the URL. */
internal fun adaptiveMimeType(mimeType: String?): String? = when (mimeType?.trim()?.lowercase()) {
    "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl" -> MimeTypes.APPLICATION_M3U8
    "application/dash+xml" -> MimeTypes.APPLICATION_MPD
    else -> null
}
