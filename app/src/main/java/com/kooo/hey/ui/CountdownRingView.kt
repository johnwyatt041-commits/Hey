package com.kooo.hey.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.kooo.hey.R

/**
 * Circular countdown/charge ring drawn around the talk button.
 *
 * Modes:
 *  - IDLE: nothing drawn
 *  - CHARGING: arc fills from 0→360 over 2 seconds (red, semi-transparent)
 *  - COUNTDOWN: arc depletes from 360→0 over 60 seconds (red→yellow, last 10s pulse)
 *  - CONTINUOUS: full ring with breathing pulse (green)
 */
class CountdownRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { IDLE, CHARGING, COUNTDOWN, CONTINUOUS }

    var mode: Mode = Mode.IDLE
        set(value) {
            field = value
            breathAnimator?.cancel()
            when (value) {
                Mode.IDLE -> {
                    sweepAngle = 0f
                    alpha = 1f
                }
                Mode.CHARGING -> {
                    sweepAngle = 0f
                    alpha = 1f
                }
                Mode.COUNTDOWN -> {
                    sweepAngle = 360f
                    alpha = 1f
                }
                Mode.CONTINUOUS -> {
                    sweepAngle = 360f
                    startBreathAnimation()
                }
            }
            invalidate()
        }

    /** Current arc sweep in degrees (0~360) */
    var sweepAngle: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 360f)
            invalidate()
        }

    /** Countdown progress 0.0~1.0 (1=full, 0=empty). Drives color interpolation. */
    var progress: Float = 1f

    private val ringPadding = 6f  // dp gap between button edge and ring
    private var breathAnimator: ValueAnimator? = null

    // Theme-aware colors
    private val colorCharging = ContextCompat.getColor(context, R.color.ring_charging)
    private val colorContinuous = ContextCompat.getColor(context, R.color.ring_continuous)
    private val colorTrack = ContextCompat.getColor(context, R.color.ring_track)
    private val colorCountdownRed = ContextCompat.getColor(context, R.color.red_primary)
    private val colorCountdownYellow = ContextCompat.getColor(context, R.color.yellow_warn)

    // Paints
    private val paintCharging = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        color = colorCharging
    }

    private val paintCountdown = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }

    private val paintContinuous = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
        color = colorContinuous
    }

    // Track (background ring, subtle)
    private val paintTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = colorTrack
    }

    private val arcRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = paintCharging.strokeWidth / 2f + ringPadding * resources.displayMetrics.density
        arcRect.set(pad, pad, w - pad, h - pad)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (mode == Mode.IDLE) return

        // Draw background track ring (subtle guide)
        if (mode == Mode.CHARGING || mode == Mode.COUNTDOWN) {
            canvas.drawOval(arcRect, paintTrack)
        }

        val startAngle = -90f  // 12 o'clock

        when (mode) {
            Mode.CHARGING -> {
                canvas.drawArc(arcRect, startAngle, sweepAngle, false, paintCharging)
            }
            Mode.COUNTDOWN -> {
                // Color: interpolate red → yellow as progress goes 1→0
                progress = (sweepAngle / 360f).coerceIn(0f, 1f)
                paintCountdown.color = lerpColor(colorCountdownRed, colorCountdownYellow, 1f - progress)
                canvas.drawArc(arcRect, startAngle, sweepAngle, false, paintCountdown)
            }
            Mode.CONTINUOUS -> {
                canvas.drawArc(arcRect, startAngle, 360f, false, paintContinuous)
            }
            else -> {}
        }
    }

    private fun startBreathAnimation() {
        breathAnimator?.cancel()
        breathAnimator = ValueAnimator.ofFloat(0.45f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                this@CountdownRingView.alpha = anim.animatedValue as Float
            }
            start()
        }
    }

    /** Start a last-10-seconds pulse effect */
    fun startUrgentPulse() {
        breathAnimator?.cancel()
        breathAnimator = ValueAnimator.ofFloat(0.3f, 1f).apply {
            duration = 500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim ->
                this@CountdownRingView.alpha = anim.animatedValue as Float
            }
            start()
        }
    }

    fun stopAnimations() {
        breathAnimator?.cancel()
        breathAnimator = null
        alpha = 1f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimations()
    }

    private fun lerpColor(c1: Int, c2: Int, t: Float): Int {
        val a = lerp((c1 shr 24) and 0xFF, (c2 shr 24) and 0xFF, t)
        val r = lerp((c1 shr 16) and 0xFF, (c2 shr 16) and 0xFF, t)
        val g = lerp((c1 shr 8) and 0xFF, (c2 shr 8) and 0xFF, t)
        val b = lerp(c1 and 0xFF, c2 and 0xFF, t)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt().coerceIn(0, 255)
}
