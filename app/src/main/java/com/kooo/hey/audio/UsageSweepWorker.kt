package com.kooo.hey.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.sin

/**
 * Standalone entry point for `app_process` that tests AudioTrack creation
 * for a range of usage values. Runs in a **separate process** so that native
 * crashes (SIGABRT/SIGSEGV from invalid usage values) only kill this process,
 * not the main application.
 *
 * Usage:
 *   export CLASSPATH="$APK"
 *   app_process /system/bin com.kooo.hey.audio.UsageSweepWorker <from> <to> [toneDurationMs]
 *
 * Each tested usage outputs one line to stdout:
 *   RESULT|<usage>|OK|<deviceType>|<address>
 *   RESULT|<usage>|ERROR|<reason>
 * When all values are tested successfully, a final line is written:
 *   DONE
 */
object UsageSweepWorker {

    private const val SAMPLE_RATE = 48000
    private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    @JvmStatic
    fun main(args: Array<String>) {
        val from = args.getOrNull(0)?.toIntOrNull() ?: 0
        val to = args.getOrNull(1)?.toIntOrNull() ?: 100
        val toneDurationMs = args.getOrNull(2)?.toIntOrNull() ?: 800

        for (usage in from..to) {
            testUsage(usage, toneDurationMs)
            System.out.flush()
        }

        println("DONE")
        System.out.flush()
    }

    private fun testUsage(usage: Int, toneDurationMs: Int) {
        try {
            val attrs = buildAttributesWithReflection(usage)
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(ENCODING)
                .setChannelMask(CHANNEL_OUT)
                .build()

            val bufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(bufSize, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                println("RESULT|$usage|ERROR|init_failed")
                track.release()
                return
            }

            track.play()

            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                println("RESULT|$usage|ERROR|play_failed")
                track.release()
                return
            }

            val routed = track.routedDevice
            val typeName = if (routed != null) deviceTypeName(routed.type) else "UNKNOWN"
            val address = routed?.address ?: ""

            playTone(track, toneDurationMs)

            track.stop()
            track.release()

            println("RESULT|$usage|OK|$typeName|$address")
        } catch (t: Throwable) {
            println("RESULT|$usage|ERROR|${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun buildAttributesWithReflection(usage: Int): AudioAttributes {
        val builder = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)

        try {
            val field = AudioAttributes.Builder::class.java.getDeclaredField("mUsage")
            field.isAccessible = true
            field.setInt(builder, usage)
        } catch (_: Throwable) {
            builder.setUsage(usage)
        }

        return builder.build()
    }

    private fun playTone(track: AudioTrack, durationMs: Int) {
        val frequency = 660.0
        val totalSamples = SAMPLE_RATE * durationMs / 1000
        val buffer = ShortArray(1024)
        var written = 0
        while (written < totalSamples) {
            val toWrite = minOf(buffer.size, totalSamples - written)
            for (i in 0 until toWrite) {
                val angle = 2.0 * Math.PI * frequency * (written + i) / SAMPLE_RATE
                buffer[i] = (sin(angle) * Short.MAX_VALUE * 0.3).toInt().toShort()
            }
            val w = track.write(buffer, 0, toWrite)
            if (w < 0) break
            written += w
        }
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUS -> "BUS"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        else -> "TYPE_$type"
    }
}
