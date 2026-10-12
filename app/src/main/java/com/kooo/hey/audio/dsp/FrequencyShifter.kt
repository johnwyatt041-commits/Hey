package com.kooo.hey.audio.dsp

/**
 * Single-sideband frequency shifter for anti-howling in PA systems.
 *
 * Shifts all frequencies up by a constant offset (default 5 Hz).
 * Each mic→speaker round trip accumulates more shift, breaking the
 * constructive interference that causes howling/feedback oscillation.
 *
 * Implementation: FIR Hilbert transform + analytic signal SSB modulation.
 * y(t) = x(t-d)*cos(θ) - H{x}(t)*sin(θ)  where θ = 2π·Δf·t/fs
 */
class FrequencyShifter(
    private val sampleRate: Int,
    shiftHz: Float = 5f
) {
    private val order = 63
    private val halfOrder = order / 2
    private val coeffs = FloatArray(order)
    private val delayLine = FloatArray(order)
    private var pos = 0

    private var phase = 0.0
    private var phaseInc = 2.0 * Math.PI * shiftHz / sampleRate

    init {
        computeCoeffs()
    }

    private fun computeCoeffs() {
        for (i in 0 until order) {
            val n = i - halfOrder
            if (n == 0 || n % 2 == 0) {
                coeffs[i] = 0f
            } else {
                val h = 2.0 / (Math.PI * n)
                val w = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / (order - 1))
                coeffs[i] = (h * w).toFloat()
            }
        }
    }

    fun setShift(hz: Float) {
        phaseInc = 2.0 * Math.PI * hz / sampleRate
    }

    fun processInPlace(buffer: ShortArray, offset: Int, length: Int) {
        val end = offset + length
        var i = offset
        while (i < end) {
            val x = buffer[i].toFloat()

            delayLine[pos] = x

            // Hilbert FIR convolution (odd taps only are non-zero)
            var hil = 0f
            var k = 1
            while (k < order) {
                var idx = pos - k
                if (idx < 0) idx += order
                hil += delayLine[idx] * coeffs[k]
                k += 2
            }

            // Group-delay compensated original
            var dIdx = pos - halfOrder
            if (dIdx < 0) dIdx += order
            val delayed = delayLine[dIdx]

            // SSB modulation: shift up by Δf
            val cosT = Math.cos(phase).toFloat()
            val sinT = Math.sin(phase).toFloat()
            val y = delayed * cosT - hil * sinT

            phase += phaseInc
            if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI

            buffer[i] = y.toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()

            pos++
            if (pos >= order) pos = 0
            i++
        }
    }

    fun reset() {
        delayLine.fill(0f)
        pos = 0
        phase = 0.0
    }
}
