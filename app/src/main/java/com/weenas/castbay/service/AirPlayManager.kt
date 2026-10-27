package com.weenas.castbay.service

import android.content.Context
import com.weenas.castbay.util.Log
import com.weenas.castbay.util.UsageStats
import android.view.Surface
import androidx.media3.exoplayer.ExoPlayer
import com.weenas.castbay.protocol.VideoPlaybackListener

class AirPlayManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AirPlayManager"
        private const val DEFAULT_VIDEO_WIDTH = 1920
        private const val DEFAULT_VIDEO_HEIGHT = 1080
        private const val PAUSE_CHECK_MS = 500L
        /** A mirroring sender reports every second; older than this, it stopped. */
        private const val SENDER_REPORT_STALE_MS = 3000L
        /**
         * A PIN left unentered this long is taken down (a new one comes with the next try).
         * It stays when the sender disconnects: iPhones do, to ask for it, then connect again.
         */
        private const val PIN_VISIBLE_MS = 60_000L
        /** The advertiser tries again for about 25 s on its own; after this the person is told. */
        private const val STARTUP_TIMEOUT_MS = 30_000L
        private const val AUTO_RETRY_MAX = 3
        private const val AUTO_RETRY_DELAY_MS = 15_000L
        private const val HEARTBEAT_CHECK_MS = 1000L
        /** A heartbeat gap longer than this is written to the log and the diagnostics. */
        private const val HEARTBEAT_GAP_NOTE_MS = 3000L
        private const val DLNA_PROGRESS_MS = 500L
        /** How long a finished DLNA video's screen waits for the next one ([StreamInfo.awaitingNext]). */
        private const val NEXT_VIDEO_WAIT_MS = 30_000L
        /** The same, quietly, after the app stops a video: iQiyi casts the next episode within a second. */
        private const val STOP_WAIT_MS = 2_000L
        private const val DLNA_SKIP_SEC = 10
        private const val MAX_COVER_BYTES = 5 * 1024 * 1024
        /** playback-info for an AirPlay sender whose video DLNA replaced: finished, so it ends its session. */
        private val AIRPLAY_VIDEO_REPLACED =
            doubleArrayOf(0.0, 0.0, 0.0, com.weenas.castbay.protocol.AirPlayNative.PLAYBACK_FINISHED, 0.0, 1.0)

        @Volatile
        private var instance: AirPlayManager? = null

        fun getInstance(context: Context): AirPlayManager {
            return instance ?: synchronized(this) {
                instance ?: AirPlayManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    /** Internal for debug builds' simulated sender, which calls its callbacks as the protocol does. */
    internal val nativeBridge = NativeBridge(
        onConnectionStarted = ::onNativeConnectionStarted,
        onVideoData = { data, pts, isH265 -> if (!endedFromTv) onNativeVideoData(data, pts, isH265) },
        onAudioData = { data, _ -> if (!endedFromTv) audioRenderer.render(data) },
        onPcmData = { data, playAtUs, compressedBytes ->
            if (!endedFromTv) onPcmAudio(data, playAtUs, compressedBytes)
        },
        onAudioFlush = {
            audioRenderer.flush()
            updateNowPlaying { it.paused() }
        },
        onVolume = { db ->
            volumeDb = db
            reportSenderVolume(AirPlayVolume.toSlider(db))
            val gain = AirPlayVolume.toGain(db)
            audioRenderer.setVolume(gain)
            hlsPlayer.setVolume(gain)
        },
        onRemoteControl = { dacpId, activeRemote -> dacp.setSender(dacpId, activeRemote) },
        onFeedback = ::onSenderHeartbeat,
        onPin = ::showPairingPin,
        onSenderReport = { report ->
            senderReport = report
            senderReportAtMs = android.os.SystemClock.elapsedRealtime()
        },
        onPaired = { device ->
            Log.i(TAG, "Paired with ${Log.personal(device.name)}")
            pairedDevices.add(device)
        },
        onClient = ::admitSender,
        audioInfo = object : AudioInfoListener {
            override fun onMetadata(dmap: ByteArray) {
                val track = DmapMetadata.parse(dmap) ?: return
                updateNowPlaying { it.copy(title = track.title, artist = track.artist, album = track.album) }
            }

            override fun onCoverArt(image: ByteArray) =
                updateNowPlaying { it.copy(coverArt = image.takeIf { bytes -> bytes.isNotEmpty() }) }

            override fun onProgress(positionSec: Double, durationSec: Double) = updateNowPlaying {
                it.copy(
                    positionSec = positionSec,
                    durationSec = durationSec,
                    // Music is played when the sender means it heard, so its position is now.
                    positionAtMs = android.os.SystemClock.elapsedRealtime()
                )
            }
        },
        videoPlayback = object : VideoPlaybackListener {
            override fun onPlay(url: String, startPositionSec: Float) = onVideoPlay(url, startPositionSec)
            // Once DLNA has taken the player over, the AirPlay sender's commands no longer apply
            // and it is told its video is over.
            override fun onSeek(positionSec: Float) {
                if (videoSource != VideoSource.DLNA) hlsPlayer.seek(positionSec)
            }
            override fun onRate(rate: Float) {
                if (videoSource != VideoSource.DLNA) hlsPlayer.setRate(rate)
            }
            override fun onStop() {
                if (videoSource != VideoSource.DLNA) onVideoStopped(null)
            }
            override fun playbackInfo(): DoubleArray =
                if (videoSource == VideoSource.DLNA) AIRPLAY_VIDEO_REPLACED else hlsPlayer.playbackInfo()
        },
        onSessionEnd = ::onNativeStreamStopped
    )
    private val discoveryAdvertiser = AirPlayDiscoveryAdvertiser(context)
    private val videoRenderer = VideoRenderer(context, onFrameSizeChanged = ::onFrameSizeChanged)
    private val audioRenderer = AudioRenderer()
    private val displayManager = context.getSystemService(android.hardware.display.DisplayManager::class.java)
    private val hevcSupport by lazy { HevcSupport.detect() }

    /** The mirroring profile offered by the running receiver; decoders are sized to it. */
    /**
     * Set when the TV ends a cast, until a sender connects again: frames still on their way
     * (a Mac keeps sending for a moment) mustn't bring the mirroring screen back.
     */
    @Volatile private var endedFromTv = false
    @Volatile private var advertised = MirroringProfile(h265 = false, width = DEFAULT_VIDEO_WIDTH, height = DEFAULT_VIDEO_HEIGHT)

    /** The codec and display size [settings] offer senders on this TV. */
    fun mirroringProfile(settings: ReceiverSettings): MirroringProfile =
        panelSize().let { (width, height) -> MirroringProfile.of(settings, hevcSupport, width, height) }

    /** What decides the mirroring offer on this TV, for checking H.265 on a new TV from the log. */
    private fun logMirroringCapabilities(settings: ReceiverSettings) {
        val modes = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY).supportedModes
            .map { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate.toInt()}" }.distinct()
        Log.i(
            TAG,
            "Mirroring offer: ${mirroringProfile(settings).label}; codec setting ${settings.videoCodec}, " +
                "resolution ${settings.resolution}; hardware HEVC decoders ${hevcSupport.decoders.ifEmpty { listOf("none") }} " +
                "(4K: ${hevcSupport.uhd}); display modes $modes"
        )
    }

    /**
     * The panel's largest mode. Many 4K TVs render their UI in a 1080p mode and switch up only
     * for video, so the current mode would under-report the panel.
     */
    private fun panelSize(): Pair<Int, Int> {
        val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val largest = display.supportedModes.maxByOrNull { it.physicalWidth.toLong() * it.physicalHeight }
            ?: display.mode
        return largest.physicalWidth to largest.physicalHeight
    }
    private val hlsPlayer = HlsPlayer(context, onFinished = ::onVideoFinished)
    @Volatile private var nowPlaying = NowPlaying()
    /** The sender's last volume (AirPlay dB), for the stats overlay. */
    @Volatile private var volumeDb: Float? = null

    /** The sender's volume ([level] 0–1); [changes] counts moves, so the TV shows each one. */
    data class SenderVolume(val level: Float, val changes: Int)
    private val _senderVolume = kotlinx.coroutines.flow.MutableStateFlow<SenderVolume?>(null)
    val senderVolume: kotlinx.coroutines.flow.StateFlow<SenderVolume?> = _senderVolume

    /**
     * A sender's volume setting. The first one only sets the level: senders send their volume
     * as they connect, and that isn't a change to show.
     */
    private fun reportSenderVolume(level: Float) {
        val previous = _senderVolume.value
        _senderVolume.value = when {
            previous == null -> SenderVolume(level, 0)
            previous.level == level -> previous
            else -> SenderVolume(level, previous.changes + 1)
        }
    }
    private val dacp = DacpClient(context)
    private val mediaSession = NowPlayingSession(context, onCommand = ::remoteControl)
    private val audioFocus = AudioFocus(context, onLost = ::onAudioFocusLost)

    /**
     * Another app started playing: music and video are paused, as other players pause for
     * CastBay (mirroring can't be, and plays on). Resuming on the phone takes the focus back.
     */
    private fun onAudioFocusLost() {
        val stream = currentStreamInfo
        when {
            stream.isAudioOnly -> remoteControl(DacpClient.Command.PAUSE)
            stream.isVideoPlayback || stream.isDlna -> hlsPlayer.setRate(0f)
        }
    }
    private val dlna = com.weenas.castbay.dlna.DlnaReceiver(context)
    /** The running receiver's settings (DLNA checks the second-device policy against them). */
    @Volatile private var activeSettings = ReceiverSettings()

    /** Who started the video in [hlsPlayer]: AirPlay (e.g. YouTube) or DLNA (e.g. Bilibili's cast button). */
    private enum class VideoSource { AIRPLAY, DLNA }
    @Volatile private var videoSource: VideoSource? = null

    /** DLNA senders' commands, played with the same player, screen and quick menu as AirPlay video. */
    private val dlnaTarget = object : com.weenas.castbay.dlna.DlnaRenderer.Target {
        @Volatile private var url: String? = null
        @Volatile private var media = com.weenas.castbay.dlna.DlnaMedia()
        @Volatile private var volume = 100
        @Volatile private var muted = false

        /** Casting verification, blocked devices and the second-device rule, as for AirPlay ([admitSender]). */
        override fun authorize(address: String, sender: String, opening: Boolean) {
            val deviceId = AccessPolicy.dlnaDeviceId(address)
            val onScreen = when {
                desktopCasting() -> desktopSenderId
                airPlayBusy() -> airPlaySenderId.ifEmpty { "airplay" }
                dlnaCasting() -> dlnaSenderId
                else -> null
            }
            val decision = AccessPolicy.decide(
                activeSettings, deviceId, knownDevices.find(deviceId), onScreen, AccessPolicy.dlnaNeedsApproval(activeSettings)
            )
            val label = Log.personal(deviceId.removePrefix("dlna:"))
            val refusal = when (decision) {
                AccessPolicy.Decision.BLOCKED -> "This device is blocked on the TV"
                AccessPolicy.Decision.BUSY -> "Another device is casting to this TV"
                AccessPolicy.Decision.ASK -> {
                    // Only a new cast asks; playback controls from a device not yet allowed are refused.
                    if (opening) {
                        Log.i(TAG, "New DLNA sender needs approval: $sender at $label")
                        _deviceRequest.value = DeviceRequest(deviceId, dlnaDeviceName(sender, address), "DLNA")
                    }
                    "Allow this device on the TV, then cast again"
                }
                AccessPolicy.Decision.ADMIT -> null
            }
            if (refusal != null) {
                Log.i(TAG, "DLNA ${if (opening) "cast" else "control"} from $sender at $label refused: $refusal")
                throw com.weenas.castbay.dlna.Soap.Fault(701, refusal)
            }
            if (!opening) return
            endDesktopSession()
            knownDevices.put(KnownDevice(deviceId, dlnaDeviceName(sender, address), "DLNA", allowed = true))
            if (_deviceRequest.value?.deviceId == deviceId) _deviceRequest.value = null
            dlnaSenderId = deviceId
        }

        override fun open(url: String, media: com.weenas.castbay.dlna.DlnaMedia) {
            this.url = url
            this.media = media
            start(url, media)
        }

        private fun start(url: String, media: com.weenas.castbay.dlna.DlnaMedia) {
            Log.i(TAG, "DLNA ${if (media.isAudio) "music" else "video"}: ${Log.personal(media.title ?: "(no title)")} · ${Log.personal(url)}")
            // Like AirPlay video, it replaces whatever is on screen. Playback starts right away:
            // some senders never send Play after SetAVTransportURI.
            videoSource = VideoSource.DLNA
            // The next video (an episode) has come.
            mainHandler.removeCallbacks(stopAwaitingNext)
            videoRenderer.stop()
            audioRenderer.stop()
            applyVolume()
            hlsPlayer.play(url, 0f, media.mimeType, media.subtitles) {
                currentError = null
                if (media.isAudio) {
                    // Music apps' casts get the music screen: cover, title, lyrics, controls.
                    nowPlaying = NowPlaying(title = media.title, artist = media.artist, album = media.album)
                    currentStreamInfo = StreamInfo(
                        sourceName = media.title.orEmpty(), isAudioOnly = true, isDlna = true,
                        sender = media.sender, nowPlaying = nowPlaying
                    )
                    mediaSession.update(nowPlaying)
                    loadDlnaCover(url, media.albumArtUrl)
                    mainHandler.removeCallbacks(dlnaMusicProgress)
                    mainHandler.post(dlnaMusicProgress)
                } else {
                    currentStreamInfo = StreamInfo(
                        sourceName = media.title.orEmpty(), isVideoPlayback = true, isDlna = true, sender = media.sender
                    )
                }
                currentState = AirPlayConnectionState.Streaming
            }
        }

        private fun ours() = videoSource == VideoSource.DLNA

        /** AirPlay is on screen: mirroring, music, or AirPlay video. */
        private fun airPlayBusy(): Boolean {
            val stream = currentStreamInfo
            return currentState == AirPlayConnectionState.Streaming && !stream.isDlna &&
                (stream.isMirroring || stream.isAudioOnly || stream.isVideoPlayback)
        }

        override fun play() {
            val progress = hlsPlayer.progress()
            if (ours() && progress.active) {
                hlsPlayer.setRate(1f)
            } else {
                // After Stop or the end, or once AirPlay took over: Play starts the video again.
                url?.let { start(it, media) }
            }
        }

        override fun pause() {
            if (ours()) hlsPlayer.setRate(0f)
        }

        override fun stop() {
            // Apps may send Stop after a video ends, before casting the next: the wait goes on
            // (the TV's Back key, or its timeout, ends it).
            if (!ours() || currentStreamInfo.awaitingNext) return
            if (currentStreamInfo.isAudioOnly) {
                onVideoStopped(null)
                return
            }
            // iQiyi moves to the next episode (at the closing credits, or when asked) with Stop
            // and, a quarter of a second later, the next video: the screen waits a moment for
            // it, without a word (a Stop usually means stop), rather than flashing the home
            // screen. The sender sees STOPPED at once.
            hlsPlayer.stop()
            mainHandler.post {
                if (dlnaOnScreen() && !currentStreamInfo.awaitingNext) awaitNext(STOP_WAIT_MS, "DLNA video stopped by the app", quietly = true)
            }
        }

        override fun seek(positionSec: Double) {
            if (ours()) hlsPlayer.seek(positionSec.toFloat())
        }

        override fun setVolume(percent: Int) {
            volume = percent
            applyVolume()
            if (ours()) reportSenderVolume(if (muted) 0f else percent / 100f)
        }

        override fun setMuted(muted: Boolean) {
            this.muted = muted
            applyVolume()
            if (ours()) reportSenderVolume(if (muted) 0f else volume / 100f)
        }

        private fun applyVolume() {
            if (ours()) hlsPlayer.setVolume(if (muted) 0f else volume / 100f)
        }

        override fun status(): com.weenas.castbay.dlna.DlnaRenderer.Status {
            val progress = hlsPlayer.progress()
            val state = when {
                !ours() || !progress.active -> com.weenas.castbay.dlna.DlnaState.STOPPED
                progress.buffering -> com.weenas.castbay.dlna.DlnaState.TRANSITIONING
                progress.playing -> com.weenas.castbay.dlna.DlnaState.PLAYING
                else -> com.weenas.castbay.dlna.DlnaState.PAUSED
            }
            return com.weenas.castbay.dlna.DlnaRenderer.Status(
                state = state,
                positionSec = if (ours()) progress.positionSec else 0.0,
                durationSec = if (ours()) progress.durationSec else 0.0,
                volume = volume,
                muted = muted
            )
        }
    }

    /** The AirPlay video player while one is active. Main thread only. */
    val videoPlayer: ExoPlayer? get() = hlsPlayer.player
    private val settingsStore = ReceiverSettingsStore(context)

    private val _stateCallbacks = mutableListOf<(AirPlayConnectionState, StreamInfo, String?) -> Unit>()

    private var currentState: AirPlayConnectionState = AirPlayConnectionState.Idle
        set(value) {
            val was = field
            field = value
            // Usage statistics (counted only if the person turned them on).
            if (value == AirPlayConnectionState.Streaming && was != AirPlayConnectionState.Streaming) {
                val stream = currentStreamInfo
                // The statistics have no counter for computers yet.
                if (!stream.isDesktop) UsageStats.castStarted(
                    if (stream.isDlna) "dlna" else "airplay",
                    when {
                        stream.isVideoPlayback -> "video"
                        stream.isAudioOnly -> "music"
                        else -> "mirror"
                    }
                )
            } else if (was == AirPlayConnectionState.Streaming && value != AirPlayConnectionState.Streaming) {
                UsageStats.castEnded()
            }
            // Held while anything is cast; given back once nothing is.
            when (value) {
                AirPlayConnectionState.Streaming -> audioFocus.acquire()
                AirPlayConnectionState.Connecting, AirPlayConnectionState.Connected -> Unit
                else -> audioFocus.release()
            }
            notifyStateChange(value)
        }

    @Volatile private var currentStreamInfo: StreamInfo = StreamInfo()
    /** The name of the AirPlay sender that set up the latest session, for [StreamInfo.sender]. */
    @Volatile private var airPlaySender = ""
    /** The AirPlay device ID of that sender: a different one is a takeover. */
    @Volatile private var airPlaySenderId = ""
    private var currentError: String? = null
    /** The native AirPlay listener's port from the latest start; null when it didn't start. */
    @Volatile private var listenerPort: Int? = null

    /** What the Diagnostics network check needs; reading it changes nothing. */
    data class ReceiverSnapshot(
        val state: AirPlayConnectionState,
        val error: String?,
        val listenerPort: Int?,
        val discovery: DiscoveryStatus
    )

    fun snapshot() = ReceiverSnapshot(currentState, currentError, listenerPort, discoveryAdvertiser.status)

    val isDiscoveryOnly: Boolean
        get() = currentState == AirPlayConnectionState.Registering ||
            currentState == AirPlayConnectionState.AdvertisingOnly

    init {
        Log.init(context)
        com.weenas.castbay.util.Diagnostics.init(context)
        nativeBridge.initialize(context)
    }

    fun start(settings: ReceiverSettings = settingsStore.load()): Boolean {
        Log.d(TAG, "Starting AirPlay server: ${settings.advertisedName}")
        // Already up (the app started it without the service, or the service asks a second time).
        if (currentState != AirPlayConnectionState.Idle && currentState != AirPlayConnectionState.Error) {
            Log.d(TAG, "The receiver is already running")
            return true
        }
        // For looking at the error screen on a healthy device (debug builds only).
        if (com.weenas.castbay.BuildConfig.DEBUG &&
            context.getSharedPreferences("airplay_identity", Context.MODE_PRIVATE).getBoolean("force_error", false)
        ) {
            activeSettings = settings
            mainHandler.post { onNativeError("Forced error (debug build)") }
            return true
        }
        logMirroringCapabilities(settings)
        activeSettings = settings
        currentError = null
        val protocolPort = nativeBridge.start(
            settings.advertisedName,
            discoveryAdvertiser.hardwareAddress(),
            mirroringProfile(settings).also { advertised = it },
            settings.maxFps(),
            settings.requiredPassword(),
            settings.usesPin(),
            pairedDevices.load().map { it.publicKey },
            settings.allowTakeover
        )
        listenerPort = protocolPort.takeIf { it > 0 }
        if (!discoveryAdvertiser.start(
                settings.advertisedName,
                protocolPort.takeIf { it > 0 },
                records = nativeBridge.discoveryRecords() ?: DiscoveryRecords.FALLBACK,
                onReady = {
                    Log.i(TAG, "AirPlay discovery records are visible on the local network")
                    mainHandler.removeCallbacks(startupWatchdog)
                    autoRetries = 0
                    if (currentState == AirPlayConnectionState.Registering) {
                        currentState = if (protocolPort > 0) {
                            AirPlayConnectionState.Discovering
                        } else {
                            AirPlayConnectionState.AdvertisingOnly
                        }
                    }
                },
                onError = ::onNativeError
            )) {
            currentError = context.getString(com.weenas.castbay.R.string.error_discovery)
            currentState = AirPlayConnectionState.Error
            return false
        }
        currentState = AirPlayConnectionState.Registering
        mainHandler.removeCallbacks(startupWatchdog)
        mainHandler.postDelayed(startupWatchdog, STARTUP_TIMEOUT_MS)
        // DLNA (video apps' own cast buttons, e.g. Bilibili's) runs beside AirPlay.
        if (settings.dlnaEnabled) Thread({ dlna.start(settings.advertisedName, dlnaTarget) }, "DLNA-start").start()
        // CastBay Desktop: computers casting over CastBay Link.
        if (settings.desktopEnabled) {
            Thread({
                try {
                    val port = linkServer.start()
                    linkDiscovery.start(settings.advertisedName, port, linkServer.identityId, settings)
                } catch (error: Throwable) {
                    // Whatever an old Android's Keystore or TLS does, AirPlay and DLNA go on.
                    Log.e(TAG, "CastBay Link did not start", error)
                }
            }, "Link-start").start()
        }
        return true
    }

    /** Applies changed settings to a running receiver; senders reconnect to the new one. */
    fun restartIfRunning(settings: ReceiverSettings) {
        if (currentState == AirPlayConnectionState.Idle || currentState == AirPlayConnectionState.Error) return
        Log.d(TAG, "Restarting AirPlay server to apply settings")
        stop()
        start(settings)
    }

    /**
     * Forgets every paired sender: they enter a PIN again. The TV takes a new pairing
     * identity too, or phones that kept the old pairing would fail to connect instead.
     */
    private fun forgetPairedDevices() {
        Log.i(TAG, "Forgetting paired devices")
        pairedDevices.clear()
        val running = currentState != AirPlayConnectionState.Idle && currentState != AirPlayConnectionState.Error
        if (running) stop()
        nativeBridge.resetIdentity()
        if (running) start(activeSettings)
    }

    private val pairedDevices = PairedDevices(context)
    private val _pairingPin = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** A PIN a new sender must enter, shown on the TV while it pairs; null otherwise. */
    val pairingPin: kotlinx.coroutines.flow.StateFlow<String?> = _pairingPin
    private val hidePinLater = Runnable { hidePairingPin() }

    private fun showPairingPin(pin: String) {
        _pairingPin.value = pin
        mainHandler.removeCallbacks(hidePinLater)
        mainHandler.postDelayed(hidePinLater, PIN_VISIBLE_MS)
    }

    fun hidePairingPin() {
        mainHandler.removeCallbacks(hidePinLater)
        _pairingPin.value = null
    }

    fun stop() {
        Log.d(TAG, "Stopping AirPlay server")
        mainHandler.removeCallbacks(startupWatchdog)
        mainHandler.removeCallbacks(autoRetry)
        nativeBridge.stop()
        hidePairingPin()
        discoveryAdvertiser.stop()
        dlna.stop()
        linkDiscovery.stop()
        linkServer.stop()
        desktopSession = null
        desktopSenderId = ""
        videoRenderer.stop()
        audioRenderer.stop()
        hlsPlayer.stop()
        videoSource = null
        nowPlaying = NowPlaying()
        dacp.clear()
        mediaSession.update(null)
        currentStreamInfo = StreamInfo()
        currentError = null
        listenerPort = null
        currentState = AirPlayConnectionState.Idle
    }

    fun registerStateCallback(callback: (AirPlayConnectionState, StreamInfo, String?) -> Unit) {
        _stateCallbacks.add(callback)
        callback(currentState, currentStreamInfo, currentError)
    }

    fun unregisterStateCallback(callback: (AirPlayConnectionState, StreamInfo, String?) -> Unit) {
        _stateCallbacks.remove(callback)
    }

    private fun notifyStateChange(state: AirPlayConnectionState) {
        _stateCallbacks.forEach { it(state, currentStreamInfo, currentError) }
    }

    fun onNativeStreamStarted(name: String, model: String, width: Int, height: Int, fps: Int,
                              sampleRate: Int, channels: Int, isMirroring: Boolean) {
        if (isMirroring && width > 0 && height > 0) {
            videoRenderer.configure(width, height)
        }
        stopDlnaVideo()
        currentStreamInfo = StreamInfo(name, model, width, height, fps, sampleRate, channels, isMirroring, true, sender = airPlaySender)
        currentError = null
        currentState = AirPlayConnectionState.Streaming
    }

    fun onNativeStreamStopped() {
        // An AirPlay session that ended while a computer casts must not clear its screen.
        if (desktopCasting()) return
        lastHeartbeatAtMs = 0L
        videoRenderer.stop()
        audioRenderer.stop()
        nowPlaying = NowPlaying()
        dacp.clear()
        mediaSession.update(null)
        // An AirPlay session ending (e.g. a phone that was only probing) leaves DLNA video playing.
        if (dlnaOnScreen()) return
        hlsPlayer.stop()
        currentStreamInfo = StreamInfo()
        currentState = AirPlayConnectionState.Discovering
    }

    private fun onNativeConnectionStarted() {
        endedFromTv = false
        currentError = null
        // A new sender's first poll mustn't be told about a video that finished before it came.
        hlsPlayer.forgetFinished()
        // DLNA video stays on screen until the AirPlay sender actually streams something.
        if (dlnaOnScreen()) return
        currentState = AirPlayConnectionState.Connecting
    }

    fun onNativeError(error: String) {
        UsageStats.failure("receiver")
        discoveryAdvertiser.stop()
        videoRenderer.stop()
        audioRenderer.stop()
        hlsPlayer.stop()
        currentError = error
        currentState = AirPlayConnectionState.Error
        scheduleAutoRetry()
    }

    /**
     * Starting must not hang: the advertiser retries by itself, and if discovery is still not up
     * after [STARTUP_TIMEOUT_MS] the person gets an error with Retry instead of "Starting…" for ever.
     */
    private val startupWatchdog = Runnable {
        if (currentState == AirPlayConnectionState.Registering) {
            com.weenas.castbay.util.Diagnostics.record("startup", "Local network discovery was not ready after ${STARTUP_TIMEOUT_MS / 1000} s")
            onNativeError(context.getString(com.weenas.castbay.R.string.error_discovery_timeout))
        }
    }

    private var autoRetries = 0

    /** A TV nobody is watching (a car, a living room) starts again by itself, a few times, with growing waits. */
    private val autoRetry = Runnable {
        if (currentState != AirPlayConnectionState.Error || autoRetries >= AUTO_RETRY_MAX) return@Runnable
        autoRetries++
        com.weenas.castbay.util.Diagnostics.record("startup", "Starting again by itself ($autoRetries of $AUTO_RETRY_MAX)")
        Log.i(TAG, "Starting the receiver again after an error ($autoRetries of $AUTO_RETRY_MAX)")
        stop()
        start(activeSettings)
    }

    private fun scheduleAutoRetry() {
        mainHandler.removeCallbacks(autoRetry)
        if (autoRetries < AUTO_RETRY_MAX) mainHandler.postDelayed(autoRetry, AUTO_RETRY_DELAY_MS * (autoRetries + 1))
    }

    fun setVideoSurface(surface: Surface?) {
        videoRenderer.setSurface(surface)
    }

    private fun onVideoPlay(url: String, startPositionSec: Float) {
        // AirPlay video replaces any mirroring session (or DLNA video) on this receiver.
        videoSource = VideoSource.AIRPLAY
        mainHandler.removeCallbacks(stopAwaitingNext)
        videoRenderer.stop()
        audioRenderer.stop()
        hlsPlayer.play(url, startPositionSec) {
            currentStreamInfo = StreamInfo(isVideoPlayback = true, sender = airPlaySender)
            currentError = null
            currentState = AirPlayConnectionState.Streaming
        }
    }

    /**
     * Audio streaming (music apps) has no picture, so the first PCM frame switches the screen
     * from "Connecting" to what is playing.
     */
    private fun onPcmAudio(pcm: ByteArray, playAtUs: Long, compressedBytes: Int) {
        audioRenderer.renderPcm(pcm, playAtUs, compressedBytes)
        val now = android.os.SystemClock.elapsedRealtime()
        lastAudioAtMs = now
        // Heard when the sender means it to be, so the position counts from then.
        if (!nowPlaying.playing) {
            updateNowPlaying { it.resumed(now + audioRenderer.musicDelayMs()) }
            // Resumed after another app took the focus (no-op while CastBay holds it).
            audioFocus.acquire()
        }
        if (currentState == AirPlayConnectionState.Connecting || videoSource == VideoSource.DLNA) {
            stopDlnaVideo()
            currentStreamInfo = StreamInfo(isAudioOnly = true, sender = airPlaySender, nowPlaying = nowPlaying)
            currentState = AirPlayConnectionState.Streaming
            mediaSession.update(nowPlaying)
            mainHandler.removeCallbacks(pauseWatchdog)
            mainHandler.postDelayed(pauseWatchdog, PAUSE_CHECK_MS)
        }
    }

    @Volatile private var lastAudioAtMs = 0L
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Freezes the now-playing progress when audio stops arriving (the sender paused). */
    private val pauseWatchdog = object : Runnable {
        override fun run() {
            // DLNA music plays here, not from the sender, so no audio gap means a pause there.
            if (!currentStreamInfo.isAudioOnly || currentStreamInfo.isDlna) return
            val lastAudio = lastAudioAtMs
            // The last audio to arrive is heard later, when the sender means it to be.
            if (nowPlaying.stalled(lastAudio)) updateNowPlaying { it.paused(nowMs = lastAudio + audioRenderer.musicDelayMs()) }
            mainHandler.postDelayed(this, PAUSE_CHECK_MS)
        }
    }

    /** When the sender's last heartbeat arrived; 0 when none is expected (no AirPlay sender). */
    @Volatile private var lastHeartbeatAtMs = 0L

    private fun onSenderHeartbeat() {
        val first = lastHeartbeatAtMs == 0L
        val now = android.os.SystemClock.elapsedRealtime()
        val gap = now - lastHeartbeatAtMs
        lastHeartbeatAtMs = now
        // A long quiet spell that ended on its own: what the TV's own Wi-Fi and the audio were doing.
        if (!first && gap > HEARTBEAT_GAP_NOTE_MS) {
            val audio = if (lastAudioAtMs == 0L) "no audio" else "audio last arrived ${now - lastAudioAtMs} ms ago"
            Log.i(TAG, "Heartbeat came back after $gap ms ($audio; ${wifiSummary()})")
            com.weenas.castbay.util.Diagnostics.record("heartbeat", "Came back after $gap ms ($audio; ${wifiSummary()})")
        }
        if (first) mainHandler.post {
            mainHandler.removeCallbacks(heartbeatWatchdog)
            mainHandler.postDelayed(heartbeatWatchdog, HEARTBEAT_CHECK_MS)
        }
    }

    /**
     * Ends casting when the sender's heartbeats stop: a phone that leaves Wi-Fi or gives up on
     * AirPlay (and plays on itself) sends nothing more, not even a disconnect, and the TV
     * would otherwise stay on its last screen. Only for music and mirroring, whose senders
     * beat every two seconds even while paused; missing two means it has gone.
     */
    private val heartbeatWatchdog = object : Runnable {
        override fun run() {
            val last = lastHeartbeatAtMs
            if (last == 0L) return
            val stream = currentStreamInfo
            val watched = currentState == AirPlayConnectionState.Streaming && !stream.isDlna &&
                (stream.isAudioOnly || stream.isMirroring)
            val now = android.os.SystemClock.elapsedRealtime()
            val silentMs = now - last
            val sinceAudio = if (lastAudioAtMs == 0L) null else now - lastAudioAtMs
            if (watched && SenderSilence.gone(stream.isAudioOnly, silentMs, sinceAudio)) {
                val audio = if (lastAudioAtMs == 0L) "no audio" else "audio last arrived ${now - lastAudioAtMs} ms ago"
                Log.i(TAG, "No heartbeat from the AirPlay sender for $silentMs ms: it has gone ($audio; ${wifiSummary()})")
                com.weenas.castbay.util.Diagnostics.record("heartbeat", "Gone after $silentMs ms ($audio; ${wifiSummary()})")
                endCasting()
                return
            }
            mainHandler.postDelayed(this, HEARTBEAT_CHECK_MS)
        }
    }

    /** The TV's own Wi-Fi: the access point it is on and how strong, to tell its roaming from the phone's. */
    @Suppress("DEPRECATION")
    private fun wifiSummary(): String = runCatching {
        val manager = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val info = manager.connectionInfo
        "TV Wi-Fi ${info.bssid} ${info.rssi} dBm ${info.linkSpeed} Mbps"
    }.getOrDefault("TV Wi-Fi unknown")

    /** A "stats for nerds" snapshot of the current stream. Main thread; null when idle. */
    fun playbackStats(): PlaybackStats? {
        val stream = currentStreamInfo
        val stats = when {
            stream.isVideoPlayback ->
                hlsPlayer.stats(if (stream.isDlna) "DLNA video" else "AirPlay video") ?: return null
            stream.isAudioOnly && stream.isDlna -> hlsPlayer.stats("DLNA audio") ?: return null
            stream.isMirroring -> PlaybackStats(
                "Screen mirroring", videoRenderer.stats(), audioRenderer.stats(), senderReportLines()
            )
            stream.isAudioOnly -> PlaybackStats("AirPlay audio", audio = audioRenderer.stats())
            else -> return null
        }
        val volume = volumeDb?.let { db ->
            if (db <= AirPlayVolume.MIN_DB) "muted" else "%.1f dB".format(java.util.Locale.US, db)
        }
        return if (volume == null) stats else stats.copy(extra = stats.extra + ("Volume" to volume))
    }

    /** The mirroring sender's latest report, for the stats overlay. */
    @Volatile private var senderReport: SenderReport? = null
    @Volatile private var senderReportAtMs = 0L

    /** The sender's side of mirroring, while its reports are current (they come every second). */
    private fun senderReportLines(): List<Pair<String, String>> {
        val report = senderReport ?: return emptyList()
        if (android.os.SystemClock.elapsedRealtime() - senderReportAtMs > SENDER_REPORT_STALE_MS) return emptyList()
        val frames = listOfNotNull(
            "${report.sentFps}/${report.targetFps} fps",
            report.screenFps?.let { "screen $it" },
            "dropped ${report.droppedFps}"
        )
        val network = listOfNotNull(
            report.roundTripMs?.let { "RTT $it ms" },
            report.lossPercent?.let { "loss %.2f%%".format(java.util.Locale.US, it) },
            report.usedBps?.let { used ->
                val capacity = report.capacityBps?.let { " / " + StatsFormat.bitrate(it) }.orEmpty()
                StatsFormat.bitrate(used) + capacity
            }
        )
        return listOf("Sender" to frames.joinToString(" · "), "Network" to network.joinToString(" · "))
    }

    /** TV-remote control of AirPlay video (e.g. YouTube): pause/resume. */
    fun toggleVideoPause() = hlsPlayer.togglePause()

    /** TV-remote control of AirPlay video: skip by [deltaSec] (negative rewinds). */
    fun seekVideoBy(deltaSec: Int) = hlsPlayer.seekBy(deltaSec)

    /** Controls the sender's playback (music apps), from the TV remote or the screen. */
    fun remoteControl(command: DacpClient.Command) {
        Log.d(TAG, "Remote control: ${command.path}")
        if (dlnaMusicPlaying()) {
            // DLNA music plays in this app; track changes belong to the sender's playlist.
            when (command) {
                DacpClient.Command.PLAY_PAUSE -> hlsPlayer.togglePause()
                DacpClient.Command.PLAY -> hlsPlayer.setRate(1f)
                DacpClient.Command.PAUSE -> hlsPlayer.setRate(0f)
                else -> Unit
            }
            return
        }
        // The sender pauses and resumes, as with an Apple TV: Apple Music flushes at once, and
        // is silent; others (NetEase Cloud Music) stop sending, and what came plays out. Paused
        // here instead, music was lost or played twice on resuming, as senders resume from
        // different places.
        dacp.send(command)
    }

    /**
     * Skips DLNA music ten seconds forward or back. AirPlay has no seeking: iPhones refuse a
     * time, and scanning forward and resuming skipped unevenly, so the phone does it.
     */
    /** Moves DLNA music to [positionSec] (its progress bar); AirPlay music can't be (see [skipMusic]). */
    fun seekMusic(positionSec: Double) {
        Log.d(TAG, "Remote control: seek to ${positionSec.toInt()} s")
        if (dlnaMusicPlaying()) hlsPlayer.seek(positionSec.toFloat())
    }

    fun skipMusic(forward: Boolean) {
        Log.d(TAG, "Remote control: skip ${if (forward) "forward" else "back"}")
        if (dlnaMusicPlaying()) hlsPlayer.seekBy(if (forward) DLNA_SKIP_SEC else -DLNA_SKIP_SEC)
    }

    /**
     * Whether a sender setting up a session may cast: blocked devices never; new ones, while
     * they need approval, only once allowed on the TV (asked now, so they try again after).
     * Must answer at once (the protocol's only thread is waiting).
     */
    private fun admitSender(deviceId: String, name: String, model: String): Boolean {
        val label = name.trim().ifEmpty { model }
        // A second AirPlay sender is refused (or let take over) by UxPlay; one while DLNA plays, here.
        val decision = AccessPolicy.decide(
            activeSettings, deviceId, knownDevices.find(deviceId),
            dlnaSenderId.takeIf { dlnaCasting() } ?: desktopSenderId.takeIf { desktopCasting() },
            AccessPolicy.airPlayNeedsApproval(activeSettings)
        )
        when (decision) {
            AccessPolicy.Decision.BLOCKED -> {
                Log.i(TAG, "Refused blocked AirPlay sender: ${Log.personal(label)} ($model)")
                return false
            }
            AccessPolicy.Decision.BUSY -> {
                Log.i(TAG, "Refused AirPlay sender ${Log.personal(label)} ($model): another device is casting")
                return false
            }
            AccessPolicy.Decision.ASK -> {
                Log.i(TAG, "New AirPlay sender needs approval: ${Log.personal(label)} ($model)")
                _deviceRequest.value = DeviceRequest(deviceId, label, model)
                return false
            }
            AccessPolicy.Decision.ADMIT -> {}
        }
        Log.i(TAG, "AirPlay sender: ${Log.personal(label)} ($model)")
        knownDevices.put(KnownDevice(deviceId, label, model, allowed = true))
        // Admitted: a PIN it was entering, or a request it made, is done with.
        hidePairingPin()
        if (_deviceRequest.value?.deviceId == deviceId) _deviceRequest.value = null
        endDesktopSession()
        val previous = airPlaySenderId
        airPlaySender = label
        airPlaySenderId = deviceId
        if (previous.isNotEmpty() && deviceId != previous) onSenderTakeover()
        return true
    }

    private val knownDevices = KnownDevices(context)

    // CastBay Desktop (CastBay Link): the receiver side of a computer casting its screen.
    @Volatile private var desktopSession: Any? = null
    @Volatile private var desktopSenderId = ""

    /** Starting and ending a desktop session (the decoder takes a while to stop) never overlap. */
    private val desktopLock = Any()

    private fun desktopCasting() = desktopSenderId.isNotEmpty()

    /** An AirPlay session is on screen (mirroring, music or video). */
    private fun airPlayStreaming(): Boolean {
        val stream = currentStreamInfo
        return currentState == AirPlayConnectionState.Streaming && !stream.isDlna && desktopSenderId.isEmpty() &&
            (stream.isMirroring || stream.isAudioOnly || stream.isVideoPlayback)
    }

    /** Another sender takes the screen: a desktop session makes way for it. */
    private fun endDesktopSession() {
        if (!desktopCasting()) return
        Log.i(TAG, "Desktop session ends: another sender took over")
        desktopSession = null
        desktopSenderId = ""
        linkServer.endSession()
    }

    private val linkHost = object : com.weenas.castbay.service.desktop.LinkHost {
        override val receiverName: String get() = activeSettings.advertisedName

        override fun settings() = activeSettings

        override fun admission(deviceId: String, name: String, paired: Boolean): com.weenas.castbay.service.desktop.LinkAdmission {
            val settings = activeSettings
            val onScreen = when {
                desktopCasting() -> desktopSenderId
                dlnaCasting() -> dlnaSenderId
                airPlayStreaming() -> airPlaySenderId.ifEmpty { "airplay" }
                else -> null
            }
            val decision = AccessPolicy.decide(
                settings, deviceId, knownDevices.find(deviceId), onScreen, AccessPolicy.desktopNeedsApproval(settings) && !paired
            )
            Log.d(TAG, "Desktop sender $deviceId: $decision (on screen: ${onScreen ?: "nothing"}; state $currentState, ${currentStreamInfo.copy(sourceName = "")})")
            return when (decision) {
                AccessPolicy.Decision.BLOCKED -> com.weenas.castbay.service.desktop.LinkAdmission.BLOCKED
                AccessPolicy.Decision.BUSY -> com.weenas.castbay.service.desktop.LinkAdmission.BUSY
                AccessPolicy.Decision.ASK -> com.weenas.castbay.service.desktop.LinkAdmission.NEEDS_APPROVAL
                AccessPolicy.Decision.ADMIT ->
                    if (!paired && AccessPolicy.desktopNeedsCode(settings)) com.weenas.castbay.service.desktop.LinkAdmission.NEEDS_CODE
                    else com.weenas.castbay.service.desktop.LinkAdmission.ADMIT
            }
        }

        override fun requestApproval(deviceId: String, name: String) {
            Log.i(TAG, "New desktop sender needs approval: ${Log.personal(name)}")
            _deviceRequest.value = DeviceRequest(deviceId, name, "CastBay Desktop")
        }

        override fun showCode(code: String) = showPairingPin(code)

        override fun hideCode() = hidePairingPin()

        override fun remember(deviceId: String, name: String) {
            knownDevices.put(KnownDevice(deviceId, name, "CastBay Desktop", allowed = true))
            if (_deviceRequest.value?.deviceId == deviceId) _deviceRequest.value = null
        }

        override fun sessionStarted(session: Any, deviceId: String, name: String, width: Int, height: Int, fps: Int) {
            Log.i(TAG, "Desktop sender: ${Log.personal(name)}, ${width}x$height")
            synchronized(desktopLock) {
                // Another sender on screen (a second device taking over is allowed): it makes way.
                if (dlnaOnScreen()) stopDlnaVideo()
                if (airPlayStreaming()) nativeBridge.disconnect()
                mainHandler.removeCallbacks(stopAwaitingNext)
                desktopSession = session
                desktopSenderId = deviceId
                videoRenderer.configure(width, height)
                currentStreamInfo = StreamInfo(
                    sourceName = name, sourceModel = "CastBay Desktop", videoWidth = width, videoHeight = height,
                    videoFps = fps, audioSampleRate = 0, audioChannels = 0, isMirroring = true, isPlaying = true, isDesktop = true, sender = name
                )
                currentError = null
                currentState = AirPlayConnectionState.Streaming
            }
        }

        override fun video(data: ByteArray, presentationTimeUs: Long) = videoRenderer.render(data, presentationTimeUs)

        override fun sessionEnded(session: Any) {
            synchronized(desktopLock) {
                Log.d(TAG, "Desktop session ended (current: ${desktopSession === session})")
                if (desktopSession !== session) return
                // The screen is free at once; stopping the decoder takes a moment.
                desktopSession = null
                desktopSenderId = ""
                currentStreamInfo = StreamInfo()
                currentState = AirPlayConnectionState.Discovering
                videoRenderer.stop()
            }
        }

        override fun videoStats() = videoRenderer.stats()
    }

    private val linkServer = com.weenas.castbay.service.desktop.LinkServer(context, linkHost)
    private val linkDiscovery = com.weenas.castbay.service.desktop.LinkDiscovery(context)

    /** The DLNA sender last admitted ([AccessPolicy.dlnaDeviceId]); casting while [dlnaCasting]. */
    @Volatile private var dlnaSenderId = ""

    /** DLNA video or music is playing (until it stops or AirPlay replaces it). */
    private fun dlnaCasting() =
        currentState == AirPlayConnectionState.Streaming && currentStreamInfo.isDlna && !currentStreamInfo.awaitingNext

    /** How a DLNA sender is listed: its app ([com.weenas.castbay.dlna.DlnaSender]) and network address (it has no device name). */
    private fun dlnaDeviceName(app: String, address: String): String {
        val host = address.removePrefix("::ffff:")
        val name = com.weenas.castbay.dlna.DlnaSender.nameRes(app)?.let(context::getString)
        return if (name == null) host else "$name · $host"
    }

    /** A new device asking to cast, while new devices need approval; [allowed] once it is. */
    data class DeviceRequest(val deviceId: String, val name: String, val model: String, val allowed: Boolean = false)
    private val _deviceRequest = kotlinx.coroutines.flow.MutableStateFlow<DeviceRequest?>(null)
    val deviceRequest: kotlinx.coroutines.flow.StateFlow<DeviceRequest?> = _deviceRequest

    /** Allows or blocks the device that asked; allowed, it casts when it tries again. */
    fun answerDeviceRequest(allow: Boolean) {
        val request = _deviceRequest.value ?: return
        knownDevices.put(KnownDevice(request.deviceId, request.name, request.model, allowed = allow))
        Log.i(TAG, "${if (allow) "Allowed" else "Blocked"} ${request.model.takeIf { it == "DLNA" } ?: "AirPlay"} sender ${Log.personal(request.name)}")
        _deviceRequest.value = if (allow) request.copy(allowed = true) else null
    }

    fun dismissDeviceRequest() {
        _deviceRequest.value = null
    }

    /** Devices that have cast here, for Settings. */
    fun knownDevices(): List<KnownDevice> = knownDevices.load()

    fun setDeviceAllowed(deviceId: String, allowed: Boolean) {
        knownDevices.find(deviceId)?.let { knownDevices.put(it.copy(allowed = allowed)) }
    }

    /**
     * Forgets every device: allowed and blocked ones, and those paired by PIN, which then
     * pair again. Casting stops if PIN pairings were forgotten (the receiver restarts).
     */
    fun forgetDevices() {
        knownDevices.clear()
        linkServer.forgetPairings()
        if (pairedDevices.load().isNotEmpty()) forgetPairedDevices()
    }

    /**
     * Another device took the session over (Allow takeover): its connection opens before the
     * first one's closes, so no session ends or starts. Told apart by device ID, as two phones
     * may share a name. What is on screen is the new sender's
     * now, and what was playing was the old one's.
     */
    private fun onSenderTakeover() {
        Log.i(TAG, "AirPlay session taken over by $airPlaySender")
        if (currentState != AirPlayConnectionState.Streaming) return
        updateNowPlaying { NowPlaying() }
        currentStreamInfo = currentStreamInfo.copy(sender = airPlaySender)
        notifyStateChange(currentState)
    }

    /** Metadata can arrive before the audio does, so it is kept until the screen shows it. */
    @Synchronized
    private fun updateNowPlaying(transform: (NowPlaying) -> NowPlaying) {
        nowPlaying = transform(nowPlaying)
        if (currentStreamInfo.isAudioOnly) {
            currentStreamInfo = currentStreamInfo.copy(nowPlaying = nowPlaying)
            notifyStateChange(currentState)
            mediaSession.update(nowPlaying)
        }
    }

    /**
     * Ends whatever is being cast, from the TV (the remote's Back key or the quick menu):
     * DLNA video stops (its sender sees STOPPED), an AirPlay sender is disconnected.
     */
    fun endCasting() {
        Log.i(TAG, "Casting ended from the TV")
        if (desktopCasting()) {
            synchronized(desktopLock) {
                desktopSession = null
                desktopSenderId = ""
                currentStreamInfo = StreamInfo()
                currentState = AirPlayConnectionState.Discovering
                linkServer.endSession()
                videoRenderer.stop()
            }
            return
        }
        if (dlnaOnScreen()) {
            onVideoStopped(null)
            return
        }
        lastHeartbeatAtMs = 0L
        endedFromTv = true
        nativeBridge.disconnect()
        // Leave the screen now rather than when the connections have closed.
        videoRenderer.stop()
        audioRenderer.stop()
        hlsPlayer.stop()
        videoSource = null
        nowPlaying = NowPlaying()
        dacp.clear()
        mediaSession.update(null)
        currentStreamInfo = StreamInfo()
        currentState = AirPlayConnectionState.Discovering
    }

    /** DLNA video or music is on screen. */
    private fun dlnaOnScreen() = videoSource == VideoSource.DLNA && currentStreamInfo.isDlna

    private fun dlnaMusicPlaying() = dlnaOnScreen() && currentStreamInfo.isAudioOnly

    /**
     * Keeps DLNA music's progress and play state on the music screen and media session in step
     * with the player, refreshing only on real changes (a pause, a seek, the length arriving).
     */
    private val dlnaMusicProgress = object : Runnable {
        override fun run() {
            if (!dlnaMusicPlaying()) return
            val progress = hlsPlayer.progress()
            val now = android.os.SystemClock.elapsedRealtime()
            val shown = nowPlaying
            val playing = progress.playing && !progress.buffering
            if (playing != shown.playing ||
                kotlin.math.abs(progress.durationSec - shown.durationSec) > 0.5 ||
                kotlin.math.abs(shown.currentPositionSec(now) - progress.positionSec) > 1.5
            ) {
                updateNowPlaying {
                    it.copy(positionSec = progress.positionSec, durationSec = progress.durationSec, positionAtMs = now, playing = playing)
                }
            }
            mainHandler.postDelayed(this, DLNA_PROGRESS_MS)
        }
    }

    /** Fetches a DLNA song's cover; ignored if another song started meanwhile. */
    private fun loadDlnaCover(songUrl: String, coverUrl: String?) {
        dlnaSongUrl = songUrl
        coverUrl ?: return
        Thread({
            val bytes = runCatching {
                val connection = java.net.URL(coverUrl).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                try {
                    connection.inputStream.use { input -> input.readBytes().takeIf { it.size <= MAX_COVER_BYTES } }
                } finally {
                    connection.disconnect()
                }
            }.onFailure { Log.w(TAG, "Could not load the DLNA cover: ${it.message}") }.getOrNull() ?: return@Thread
            mainHandler.post {
                if (dlnaMusicPlaying() && dlnaSongUrl == songUrl) updateNowPlaying { it.copy(coverArt = bytes) }
            }
        }, "DLNA-cover").start()
    }

    @Volatile private var dlnaSongUrl: String? = null

    /** AirPlay mirroring or audio starting takes the screen from DLNA video. */
    private fun stopDlnaVideo() {
        if (videoSource != VideoSource.DLNA) return
        videoSource = null
        hlsPlayer.stop()
    }

    /**
     * The player is done: the video ended, or failed with [error]. A DLNA video that played to
     * its end waits for the next one (see [StreamInfo.awaitingNext]): apps such as iQiyi only
     * cast the next episode once they see the last one stopped, which takes them a few seconds.
     */
    private fun onVideoFinished(error: String?) {
        val stream = currentStreamInfo
        if (error != null || !dlnaOnScreen() || stream.isAudioOnly || currentState != AirPlayConnectionState.Streaming) {
            onVideoStopped(error)
            return
        }
        hlsPlayer.stop()
        awaitNext(NEXT_VIDEO_WAIT_MS, "DLNA video ended")
    }

    /**
     * Keeps the finished DLNA video's screen for [timeoutMs], for the app's next video
     * ([StreamInfo.awaitingNext]); [quietly], with nothing on screen. Main thread.
     */
    private fun awaitNext(timeoutMs: Long, why: String, quietly: Boolean = false) {
        Log.i(TAG, "$why; waiting ${timeoutMs / 1000} s for the next one")
        currentStreamInfo = currentStreamInfo.copy(awaitingNext = true, awaitingNextQuietly = quietly, isPlaying = false)
        notifyStateChange(currentState)
        mainHandler.removeCallbacks(stopAwaitingNext)
        mainHandler.postDelayed(stopAwaitingNext, timeoutMs)
    }

    /** No next video came: back to the home screen. */
    private val stopAwaitingNext = Runnable {
        if (currentStreamInfo.awaitingNext) {
            Log.i(TAG, "No next DLNA video; back to the home screen")
            onVideoStopped(null)
        }
    }

    /** [error] is shown on the waiting screen until the next connection. */
    private fun onVideoStopped(error: String?) {
        mainHandler.removeCallbacks(stopAwaitingNext)
        hlsPlayer.stop()
        if (currentStreamInfo.isDlna && currentStreamInfo.isAudioOnly) {
            nowPlaying = NowPlaying()
            mediaSession.update(null)
        }
        if (currentStreamInfo.isVideoPlayback || currentStreamInfo.isDlna) {
            currentStreamInfo = StreamInfo()
            currentError = error
            currentState = AirPlayConnectionState.Discovering
        }
    }

    private fun onFrameSizeChanged(width: Int, height: Int) {
        currentStreamInfo = currentStreamInfo.copy(frameWidth = width, frameHeight = height)
        notifyStateChange(currentState)
    }

    /** The protocol core forwards complete Annex B frames here for MediaCodec decoding. */
    fun onNativeVideoData(data: ByteArray, presentationTimeUs: Long, isH265: Boolean) {
        var stream = currentStreamInfo
        if (!stream.isMirroring) {
            // The decoder is sized to the display offered to senders (up to 4K with H.265).
            stream = StreamInfo(
                videoWidth = advertised.width,
                videoHeight = advertised.height,
                videoFps = 60,
                audioSampleRate = 0,
                audioChannels = 0,
                isMirroring = true,
                sender = airPlaySender
            )
            currentStreamInfo = stream
            currentState = AirPlayConnectionState.Streaming
            UsageStats.mirroring(stream.videoHeight, isH265)
        }
        videoRenderer.configure(stream.videoWidth, stream.videoHeight, isH265)
        videoRenderer.render(data, presentationTimeUs)
    }

}
