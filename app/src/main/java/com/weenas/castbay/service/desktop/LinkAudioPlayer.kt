package com.weenas.castbay.service.desktop

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import com.weenas.castbay.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays the AAC-LC audio of a desktop sender (desktop/docs/protocol.md): frames go through a
 * MediaCodec decoder to an AudioTrack as they arrive. Nothing is buffered on purpose: if the
 * sender's clock runs ahead of this device's, the oldest frames are dropped to stay live.
 */
class LinkAudioPlayer private constructor(
    private val codec: MediaCodec,
    private val track: AudioTrack
) {
    private class Frame(val data: ByteArray, val presentationTimeUs: Long)

    private val queue = ArrayBlockingQueue<Frame>(MAX_QUEUED_FRAMES)
    @Volatile private var running = true
    private val worker = Thread(::run, "Link-audio").apply { isDaemon = true }

    /** Takes a frame from the network thread; never blocks. */
    fun feed(data: ByteArray, presentationTimeUs: Long) {
        val frame = Frame(data, presentationTimeUs)
        while (!queue.offer(frame)) queue.poll()
    }

    private fun run() {
        val info = MediaCodec.BufferInfo()
        var scratch = ByteArray(8192)
        try {
            track.play()
            while (running) {
                val frame = queue.poll(50, TimeUnit.MILLISECONDS)
                if (frame != null) {
                    val index = codec.dequeueInputBuffer(20_000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index) ?: continue
                        input.clear()
                        input.put(frame.data)
                        codec.queueInputBuffer(index, 0, frame.data.size, frame.presentationTimeUs, 0)
                    }
                }
                while (true) {
                    val out = codec.dequeueOutputBuffer(info, 0)
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || out == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
                    if (out < 0) break
                    val pcm: ByteBuffer? = codec.getOutputBuffer(out)
                    if (pcm != null && info.size > 0) {
                        if (scratch.size < info.size) scratch = ByteArray(info.size)
                        pcm.position(info.offset)
                        pcm.get(scratch, 0, info.size)
                        if (running) track.write(scratch, 0, info.size)
                    }
                    codec.releaseOutputBuffer(out, false)
                }
            }
        } catch (error: Exception) {
            if (running) Log.w(TAG, "Desktop audio stopped: ${error.message}")
        } finally {
            runCatching { track.pause(); track.flush() }
            runCatching { track.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    fun stop() {
        running = false
        queue.clear()
    }

    companion object {
        private const val TAG = "CastBayLinkAudio"
        private const val MAX_QUEUED_FRAMES = 8

        /** The AudioSpecificConfig from the offer is a handful of bytes of hex (two for AAC-LC). */
        fun parseConfig(hex: String): ByteArray? {
            if (hex.length !in 4..10 || hex.length % 2 != 0) return null
            return try {
                ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            } catch (e: NumberFormatException) {
                null
            }
        }

        /** What the offer's `audio` object asks for, or null if this device cannot play it. */
        fun create(sampleRate: Int, channels: Int, configHex: String): LinkAudioPlayer? {
            if (sampleRate != 44100 && sampleRate != 48000) return null
            if (channels !in 1..2) return null
            val config = parseConfig(configHex) ?: return null
            var codec: MediaCodec? = null
            var track: AudioTrack? = null
            return try {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(config))
                    setInteger(MediaFormat.KEY_IS_ADTS, 0)
                }
                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(format, null, null, 0)
                    start()
                }
                val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                val pcm = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(mask).build()
                val minimum = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
                // A tenth of a second at least: smaller underruns on slow TV boxes.
                val buffer = maxOf(minimum, sampleRate * channels * 2 / 10)
                val builder = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
                    )
                    .setAudioFormat(pcm)
                    .setBufferSizeInBytes(buffer)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                track = builder.build()
                if (track.state != AudioTrack.STATE_INITIALIZED) throw IllegalStateException("the audio output did not start")
                LinkAudioPlayer(codec, track).also { it.worker.start() }
            } catch (error: Exception) {
                Log.w(TAG, "No desktop audio: ${error.message}")
                runCatching { track?.release() }
                runCatching { codec?.release() }
                null
            }
        }
    }
}
