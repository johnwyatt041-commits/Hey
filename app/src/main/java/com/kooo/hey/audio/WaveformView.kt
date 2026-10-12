package com.kooo.hey.audio

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.kooo.hey.R

/**
 * Real-time audio level visualization.
 *
 * singleChannelMode = true (main screen):
 *   Elegant mirrored rounded bars with gradient, transparent background.
 *
 * singleChannelMode = false (debug panel):
 *   Dual-channel: mic (green up) / output (red down) with labels.
 */
class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val maxSamplesDebug = 200
    private val maxSamplesMain = 64   // fewer, wider bars for main screen

    private val maxSamples: Int get() = if (singleChannelMode) maxSamplesMain else maxSamplesDebug

    private var inputLevels = FloatArray(maxSamplesDebug)
    private var outputLevels = FloatArray(maxSamplesDebug)
    private var writePos = 0
    private var sampleCount = 0

    var singleChannelMode = false
        set(value) {
            if (field != value) {
                field = value
                clear()
            }
        }

    // Theme-aware colors
    private val colorInput = ContextCompat.getColor(context, R.color.waveform_input)
    private val colorOutput = ContextCompat.getColor(context, R.color.waveform_output)
    private val colorLabel = ContextCompat.getColor(context, R.color.waveform_label)
    private val colorGrid = ContextCompat.getColor(context, R.color.waveform_grid)
    private val colorBg = ContextCompat.getColor(context, R.color.waveform_bg)
    private val colorLevelLow = ContextCompat.getColor(context, R.color.waveform_level_low)
    private val colorLevelMid = ContextCompat.getColor(context, R.color.waveform_level_mid)
    private val colorLevelHigh = ContextCompat.getColor(context, R.color.waveform_level_high)

    // Debug mode paints
    private val paintInput = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInput
        style = Paint.Style.FILL
    }
    private val paintOutput = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorOutput
        style = Paint.Style.FILL
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorLabel
        textSize = 28f
    }
    private val paintCenterLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorGrid
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val paintDebugBg = Paint().apply {
        color = colorBg
        style = Paint.Style.FILL
    }

    // Main screen paint (gradient applied per-bar)
    private val paintBar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun pushLevel(inputLevel: Float, outputLevel: Float) {
        val max = maxSamples
        inputLevels[writePos % inputLevels.size] = inputLevel.coerceIn(0f, 1f)
        outputLevels[writePos % outputLevels.size] = outputLevel.coerceIn(0f, 1f)
        writePos = (writePos + 1) % max
        sampleCount++
        postInvalidate()
    }

    fun clear() {
        inputLevels.fill(0f)
        outputLevels.fill(0f)
        writePos = 0
        sampleCount = 0
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (singleChannelMode) drawMainWaveform(canvas)
        else drawDebugWaveform(canvas)
    }

    // ==================== Main Screen: Elegant mirrored bars ====================

    private fun drawMainWaveform(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val midY = h / 2f
        val count = minOf(sampleCount, maxSamplesMain)
        if (count == 0) return

        val gap = 3f * resources.displayMetrics.density  // gap between bars
        val barWidth = (w - gap * (maxSamplesMain - 1)) / maxSamplesMain
        val radius = barWidth / 2f
        val halfH = midY - 2f

        for (i in 0 until count) {
            val idx = if (sampleCount >= maxSamplesMain) {
                (writePos + i) % maxSamplesMain
            } else { i }

            val level = inputLevels[idx % inputLevels.size]
            val barH = level * halfH
            if (barH < 1f) continue

            val x = i * (barWidth + gap)

            // Color based on amplitude: quiet=dim, mid=green, loud=red
            val color = when {
                level < 0.3f -> lerpColor(colorLevelLow, colorLevelMid, level / 0.3f)
                else -> lerpColor(colorLevelMid, colorLevelHigh, (level - 0.3f) / 0.7f)
            }
            paintBar.color = color

            // Alpha fade: slightly transparent for older bars (left), full for newest (right)
            val age = i.toFloat() / maxSamplesMain
            paintBar.alpha = (100 + 155 * age).toInt().coerceIn(100, 255)

            // Draw mirrored rounded bar
            canvas.drawRoundRect(
                x, midY - barH,
                x + barWidth, midY + barH,
                radius, radius, paintBar
            )
        }
    }

    // ==================== Debug Panel: Dual channel bars ====================

    private fun drawDebugWaveform(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val midY = h / 2f

        canvas.drawRect(0f, 0f, w, h, paintDebugBg)
        canvas.drawLine(0f, midY, w, midY, paintCenterLine)
        canvas.drawText("MIC", 8f, 24f, paintLabel)
        canvas.drawText("OUT", 8f, h - 8f, paintLabel)

        val count = minOf(sampleCount, maxSamplesDebug)
        if (count == 0) return

        val barWidth = w / maxSamplesDebug
        val halfH = midY - 4f

        for (i in 0 until count) {
            val idx = if (sampleCount >= maxSamplesDebug) {
                (writePos + i) % maxSamplesDebug
            } else { i }

            val x = i * barWidth

            val inH = inputLevels[idx % inputLevels.size] * halfH
            if (inH > 1f) {
                canvas.drawRect(x, midY - inH, x + barWidth - 1f, midY - 1f, paintInput)
            }
            val outH = outputLevels[idx % outputLevels.size] * halfH
            if (outH > 1f) {
                canvas.drawRect(x, midY + 1f, x + barWidth - 1f, midY + outH, paintOutput)
            }
        }
    }

    // ==================== Helpers ====================

    private fun lerpColor(c1: Int, c2: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val a = lerp((c1 shr 24) and 0xFF, (c2 shr 24) and 0xFF, tt)
        val r = lerp((c1 shr 16) and 0xFF, (c2 shr 16) and 0xFF, tt)
        val g = lerp((c1 shr 8) and 0xFF, (c2 shr 8) and 0xFF, tt)
        val b = lerp(c1 and 0xFF, c2 and 0xFF, tt)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt().coerceIn(0, 255)
}
