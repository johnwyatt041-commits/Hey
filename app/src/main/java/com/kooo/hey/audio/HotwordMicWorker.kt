package com.kooo.hey.audio

import android.media.AudioFormat
import android.media.AudioRecord

/**
 * Standalone `app_process` worker that opens AudioRecord with elevated privileges
 * (HOTWORD/1999 etc.) and streams PCM to stdout.
 *
 * Supports two modes:
 *
 * 1. **Probe mode** (`probe`): discover how many channels are available,
 *    record briefly, and report per-channel RMS levels.
 *    Output to stdout: `PROBE|<totalChannels>|ch0_rms|ch1_rms|...|DONE`
 *
 * 2. **Stream mode** (default): continuous mono PCM16LE 48kHz to stdout.
 *    All channels are mixed to mono.
 *
 * Usage:
 *   export CLASSPATH="$APK"
 *   # Probe mode — discover channels
 *   app_process /system/bin com.kooo.hey.audio.HotwordMicWorker probe [source] [rate]
 *
 *   # Stream mode — real-time audio (mix all channels)
 *   app_process /system/bin com.kooo.hey.audio.HotwordMicWorker stream [source] [rate] [port]
 */
object HotwordMicWorker {

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.getOrNull(0) ?: "stream"
        when (mode) {
            "probe" -> {
                val source = args.getOrNull(1)?.toIntOrNull() ?: 1999
                val rate = args.getOrNull(2)?.toIntOrNull() ?: 48000
                runProbe(source, rate)
            }
            else -> {
                val source = args.getOrNull(1)?.toIntOrNull() ?: 1999
                val rate = args.getOrNull(2)?.toIntOrNull() ?: 48000
                runStream(source, rate)
            }
        }
    }

    // ==================== Probe Mode ====================

    private fun runProbe(audioSource: Int, sampleRate: Int) {
        System.err.println("Probe: source=$audioSource, rate=$sampleRate")

        val result = tryOpenMaxChannels(audioSource, sampleRate)
        if (result == null) {
            println("PROBE|0|ERROR|failed_to_open")
            return
        }

        val (record, channelCount) = result
        System.err.println("Opened: channels=$channelCount")

        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            println("PROBE|$channelCount|ERROR|recording_failed")
            record.release()
            return
        }

        // Record ~1 second and compute per-channel RMS
        val framesPerRead = 1024
        val buffer = ShortArray(framesPerRead * channelCount)
        val totalSamples = sampleRate  // ~1 second
        var framesRead = 0L
        val channelSumSq = DoubleArray(channelCount)
        val channelPeak = IntArray(channelCount)

        while (framesRead < totalSamples) {
            val n = record.read(buffer, 0, buffer.size)
            if (n <= 0) break
            val frames = n / channelCount
            for (f in 0 until frames) {
                for (c in 0 until channelCount) {
                    val s = buffer[f * channelCount + c].toInt()
                    channelSumSq[c] += s.toLong() * s.toLong()
                    val abs = if (s < 0) -s else s
                    if (abs > channelPeak[c]) channelPeak[c] = abs
                }
            }
            framesRead += frames
        }

        record.stop()

        val routedDev = record.routedDevice
        val devInfo = routedDev?.let { "id=${it.id},type=${it.type},addr=${it.address}" } ?: "none"
        record.release()

        // Build output: PROBE|channels|ch0_rms|ch1_rms|...|dev=...|DONE
        val sb = StringBuilder("PROBE|$channelCount")
        for (c in 0 until channelCount) {
            val rms = if (framesRead > 0) {
                kotlin.math.sqrt(channelSumSq[c] / framesRead) / 32768.0
            } else 0.0
            sb.append("|ch$c:rms=${"%.6f".format(rms)},peak=${channelPeak[c]}")
        }
        sb.append("|dev=$devInfo|frames=$framesRead|DONE")
        println(sb.toString())
        System.out.flush()
    }

    // ==================== Stream Mode ====================

    private fun runStream(audioSource: Int, sampleRate: Int) {
        System.err.println("Stream: source=$audioSource, rate=$sampleRate")

        val result = tryOpenMaxChannels(audioSource, sampleRate)
        if (result == null) {
            System.err.println("FATAL: failed to open AudioRecord")
            System.exit(1)
            return
        }

        val (record, channelCount) = result
        System.err.println("Opened: channels=$channelCount" +
            ", routed=${record.routedDevice?.let { "id=${it.id},type=${it.type},addr=${it.address}" } ?: "none"}")

        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            System.err.println("FATAL: startRecording failed")
            record.release()
            System.exit(1)
            return
        }

        System.err.println("Recording started [MIX_ALL], streaming to stdout...")

        val framesPerRead = 1024
        val multiBuffer = ShortArray(framesPerRead * channelCount)
        val monoBuffer = ShortArray(framesPerRead)
        val outBytes = ByteArray(framesPerRead * 2)
        val stdout = System.out

        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        Thread({
            try {
                val reader = System.`in`.bufferedReader()
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    if (line.trim().uppercase() == "STOP") { running.set(false); break }
                }
            } catch (_: Throwable) {}
        }, "StdinMonitor").apply { isDaemon = true; start() }

        try {
            while (running.get()) {
                val n = record.read(multiBuffer, 0, multiBuffer.size)
                if (n <= 0) break
                val frames = n / channelCount

                when {
                    channelCount == 1 -> {
                        System.arraycopy(multiBuffer, 0, monoBuffer, 0, frames)
                    }
                    else -> {
                        // Mix all channels to mono
                        for (f in 0 until frames) {
                            var sum = 0L
                            for (c in 0 until channelCount) {
                                sum += multiBuffer[f * channelCount + c]
                            }
                            monoBuffer[f] = (sum / channelCount).toInt().toShort()
                        }
                    }
                }

                // Convert to PCM16LE bytes
                for (i in 0 until frames) {
                    val s = monoBuffer[i].toInt()
                    outBytes[i * 2] = (s and 0xFF).toByte()
                    outBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                stdout.write(outBytes, 0, frames * 2)
                stdout.flush()
            }
        } catch (e: Throwable) {
            System.err.println("Stream error: ${e.message}")
        } finally {
            running.set(false)
            record.stop()
            record.release()
            System.err.println("HotwordMicWorker exiting")
        }
    }

    // ==================== Open with max channels ====================

    /**
     * Try to open AudioRecord with the highest channel count possible.
     * Returns (AudioRecord, actualChannelCount) or null.
     */
    private fun tryOpenMaxChannels(audioSource: Int, sampleRate: Int): Pair<AudioRecord, Int>? {
        // Try from high to low channel count
        val channelMasks = listOf(
            0x3FFF to 14,  // 14ch (E5 nocodec)
            0xFF to 8,     // 8ch (07 HOTWORD)
            0x3F to 6,     // 6ch
            0x0F to 4,     // 4ch
            AudioFormat.CHANNEL_IN_STEREO to 2,
            AudioFormat.CHANNEL_IN_MONO to 1,
        )

        for ((mask, expectedCh) in channelMasks) {
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) continue

                val record = AudioRecord(
                    audioSource, sampleRate, mask,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192) * 2
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    val actual = record.channelCount
                    System.err.println("Opened: mask=0x${Integer.toHexString(mask)}" +
                        ", expected=${expectedCh}ch, actual=${actual}ch")
                    return Pair(record, actual)
                }
                record.release()
            } catch (e: Throwable) {
                System.err.println("mask=0x${Integer.toHexString(mask)} failed: ${e.message}")
            }
        }
        return null
    }
}
