package com.kooo.hey.audio.dsp

import kotlin.math.*

/**
 * 2nd-order Butterworth low-pass biquad filter.
 *
 * Cuts frequencies above [cutoffHz], ideal for removing high-frequency
 * howling/feedback in a voice PA system (human voice is mostly below 4kHz).
 *
 * Operates on PCM16 ShortArray in-place for zero-allocation real-time use.
 */
class LowPassFilter(
    private val sampleRate: Int,
    cutoffHz: Float = 4000f
) {
    // Biquad coefficients (Direct Form II Transposed)
    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    // Filter state
    private var z1 = 0f
    private var z2 = 0f

    init {
        setCutoff(cutoffHz)
    }

    /**
     * Recalculate coefficients for a new cutoff frequency.
     * Safe to call from the audio thread (no allocation).
     */
    fun setCutoff(cutoffHz: Float) {
        val fc = cutoffHz.coerceIn(200f, sampleRate / 2f - 100f)
        val w0 = 2.0 * PI * fc / sampleRate
        // Butterworth Q = 1/√2 for maximally flat passband
        val q = sqrt(2.0) / 2.0
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)

        val a0 = (1.0 + alpha).toFloat()
        b0 = ((1.0 - cosW0) / 2.0).toFloat() / a0
        b1 = (1.0 - cosW0).toFloat() / a0
        b2 = b0
        a1 = (-2.0 * cosW0).toFloat() / a0
        a2 = (1.0 - alpha).toFloat() / a0
    }

    /**
     * Filter PCM16 samples in-place.
     */
    fun processInPlace(buffer: ShortArray, offset: Int, length: Int) {
        val end = offset + length
        var i = offset
        while (i < end) {
            val x = buffer[i].toFloat()
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            buffer[i] = y.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            i++
        }
    }

    fun reset() {
        z1 = 0f
        z2 = 0f
    }
}
