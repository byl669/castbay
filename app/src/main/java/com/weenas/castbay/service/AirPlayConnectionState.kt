package com.weenas.castbay.service

enum class AirPlayConnectionState {
    Idle,
    Discovering,
    Registering,
    AdvertisingOnly,
    Connecting,
    Connected,
    Streaming,
    Disconnected,
    Error
}

data class StreamInfo(
    val sourceName: String = "",
    val sourceModel: String = "",
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoFps: Int = 0,
    val audioSampleRate: Int = 44100,
    val audioChannels: Int = 2,
    val isMirroring: Boolean = false,
    val isPlaying: Boolean = false,
    /** Visible size of the decoded picture (crop rect); 0 until the decoder reports it. */
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    /** AirPlay video (the sender streams a URL, e.g. from the YouTube app) rather than mirroring. */
    val isVideoPlayback: Boolean = false,
    /** Audio streaming without video (e.g. a music app); [nowPlaying] describes it. */
    val isAudioOnly: Boolean = false,
    /** Cast over DLNA (video, or music with [isAudioOnly]) rather than AirPlay. */
    val isDlna: Boolean = false,
    /**
     * Who is casting: the AirPlay sender's name ("eason的iPhone"), or for DLNA the casting app's
     * id from [com.weenas.castbay.dlna.DlnaSender] ("iqiyi"); empty when unknown.
     */
    val sender: String = "",
    val nowPlaying: NowPlaying = NowPlaying(),
    /**
     * A DLNA video played to its end, and the screen waits a while for the app to cast the
     * next one (an episode) rather than going back to the home screen in between.
     */
    val awaitingNext: Boolean = false,
    /** Waiting after the app stopped the video (see [awaitingNext]): briefly, and without saying so. */
    val awaitingNextQuietly: Boolean = false
)
