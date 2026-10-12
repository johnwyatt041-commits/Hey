package com.kooo.hey.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import com.kooo.hey.log.AppLog
import java.io.File
import java.nio.ByteBuffer

/**
 * Software audio player using MediaExtractor + MediaCodec (software decoder) + AudioTrack.
 * Preferred software codecs (c2.android.* / OMX.google.*) to avoid car HW decoder issues.
 */
class SoftwareAudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "SoftwareAudioPlayer"
        private const val TIMEOUT_US = 10_000L
    }

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var decodeThread: Thread? = null

    @Volatile var isPlaying = false; private set
    @Volatile var isPaused = false; private set
    @Volatile private var stopRequested = false

    var onProgress: ((currentMs: Int, totalMs: Int) -> Unit)? = null
    var onComplete: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var durationMs: Long = 0

    fun play(path: String, audioUsage: Int, volume: Float, outputDeviceId: Int) {
        stop()
        stopRequested = false

        decodeThread = Thread({
            var localExtractor: MediaExtractor? = null
            var localCodec: MediaCodec? = null
            var localTrack: AudioTrack? = null
            try {
                if (!File(path).exists()) {
                    postError("File not found: $path")
                    return@Thread
                }

                localExtractor = MediaExtractor().also { extractor = it }
                localExtractor.setDataSource(path)

                val trackIndex = findAudioTrack(localExtractor)
                if (trackIndex < 0) {
                    postError("No audio track found")
                    return@Thread
                }
                localExtractor.selectTrack(trackIndex)
                val format = localExtractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"
                durationMs = if (format.containsKey(MediaFormat.KEY_DURATION))
                    format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0

                val codecName = findSoftwareDecoder(mime)
                AppLog.i(TAG, "Using decoder: ${codecName ?: "default"} for $mime")
                localCodec = if (codecName != null) MediaCodec.createByCodecName(codecName)
                else MediaCodec.createDecoderByType(mime)
                codec = localCodec
                localCodec.configure(format, null, null, 0)
                localCodec.start()

                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
                val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO
                else AudioFormat.CHANNEL_OUT_STEREO

                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val outDevice = if (outputDeviceId > 0)
                    am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).find { it.id == outputDeviceId }
                else null
                val effectiveUsage = if (outDevice != null) 0 else audioUsage

                val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
                localTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(effectiveUsage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf * 2, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                if (outDevice != null) localTrack.preferredDevice = outDevice
                val vol = volume.coerceIn(0f, 1f)
                localTrack.setVolume(vol)
                audioTrack = localTrack
                localTrack.play()
                isPlaying = true

                AppLog.i(TAG, "Software decode started: $path (sr=$sampleRate ch=$channelCount)")
                decodeLoop(localExtractor, localCodec, localTrack)

                if (!stopRequested) {
                    isPlaying = false
                    onComplete?.invoke()
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Software decode error", e)
                if (!stopRequested) postError(e.message ?: "Decode error")
            } finally {
                try { localTrack?.stop() } catch (_: Exception) {}
                try { localTrack?.release() } catch (_: Exception) {}
                try { localCodec?.stop() } catch (_: Exception) {}
                try { localCodec?.release() } catch (_: Exception) {}
                try { localExtractor?.release() } catch (_: Exception) {}
                isPlaying = false
                isPaused = false
            }
        }, "Hey-SoftDecode").also { it.start() }
    }

    private fun decodeLoop(extractor: MediaExtractor, codec: MediaCodec, track: AudioTrack) {
        val info = MediaCodec.BufferInfo()
        var inputEos = false

        while (!stopRequested) {
            if (isPaused) {
                Thread.sleep(50)
                continue
            }

            if (!inputEos) {
                val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx) ?: continue
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEos = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outIdx >= 0) {
                val outBuf = codec.getOutputBuffer(outIdx)
                if (outBuf != null && info.size > 0) {
                    val data = ByteArray(info.size)
                    outBuf.get(data)
                    track.write(data, 0, data.size)

                    val currentMs = (info.presentationTimeUs / 1000).toInt()
                    onProgress?.invoke(currentMs, durationMs.toInt())
                }
                codec.releaseOutputBuffer(outIdx, false)

                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
    }

    fun pause() {
        if (!isPlaying) return
        isPaused = true
        try { audioTrack?.pause() } catch (_: Exception) {}
    }

    fun resume() {
        if (!isPlaying || !isPaused) return
        isPaused = false
        try { audioTrack?.play() } catch (_: Exception) {}
    }

    fun togglePause() {
        if (isPaused) resume() else pause()
    }

    fun seekTo(positionMs: Int) {
        try {
            extractor?.seekTo(positionMs.toLong() * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        } catch (_: Exception) {}
    }

    fun setVolume(volume: Float) {
        try {
            audioTrack?.setVolume(volume.coerceIn(0f, 1f))
        } catch (_: Exception) {}
    }

    fun stop() {
        stopRequested = true
        isPaused = false
        decodeThread?.join(2000)
        decodeThread = null
        extractor = null
        codec = null
        audioTrack = null
        isPlaying = false
    }

    val currentPosition: Int
        get() = try {
            val ext = extractor ?: return 0
            (ext.sampleTime / 1000).toInt().coerceAtLeast(0)
        } catch (_: Exception) { 0 }

    val duration: Int get() = durationMs.toInt()

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    private fun findSoftwareDecoder(mime: String): String? {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val softPrefixes = listOf("c2.android.", "OMX.google.")
        for (info in codecList.codecInfos) {
            if (info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            val name = info.name.lowercase()
            if (softPrefixes.any { name.startsWith(it.lowercase()) }) {
                return info.name
            }
        }
        return null
    }

    private fun postError(msg: String) {
        onError?.invoke(msg)
    }
}
