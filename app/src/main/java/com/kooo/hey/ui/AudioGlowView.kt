package com.kooo.hey.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * 水波涟漪效果 — 同心圆线从倒计时圆环外侧向外缓慢扩散。
 *
 * 美感设计：
 * - 涟漪从 CountdownRing 外侧出发，不与按钮/圆环重叠
 * - 渐显出现 + 渐隐消失，过渡柔和
 * - 线宽从粗（8dp，与倒计时圆一致）渐变到极细（0.5dp）
 * - 缓出曲线扩散，先快后慢，像真实水波
 * - 音量影响涟漪亮度和生成频率
 */
class AudioGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class Ripple(
        val birthTime: Long,
        val intensity: Float
    )

    private var rippleColor = Color.rgb(255, 59, 48)
    private val ripples = mutableListOf<Ripple>()
    private var active = false
    private var smoothedLevel = 0f
    private var lastSpawnTime = 0L
    private var spawnThreshold = 0.03f

    fun setSpawnThreshold(threshold: Float) {
        spawnThreshold = threshold
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val density = resources.displayMetrics.density

    companion object {
        const val RIPPLE_LIFESPAN_MS = 3500L     // 涟漪寿命（更长，扩散更远）
        const val MIN_SPAWN_INTERVAL_MS = 1200L   // 最小生成间隔（更大，更稀疏）
        const val MAX_RIPPLES = 3                  // 同时最多 3 圈

        // CountdownRing = 320dp, 在 600dp View 中占比 320/600 = 0.533
        // ring 外边缘半径 ≈ 160dp / 300dp = 0.533，加一点间距从 0.56 开始
        const val START_RADIUS_RATIO = 0.56f       // 从 ring 外侧开始
        const val END_RADIUS_RATIO = 0.98f         // 最大扩散

        const val MAX_STROKE_DP = 8f               // 起始线宽（与倒计时圆一致）
        const val MIN_STROKE_DP = 0.5f             // 最终线宽

        const val FADE_IN_FRACTION = 0.12f         // 前 12% 渐显
        const val FADE_OUT_START = 0.35f           // 从 35% 开始渐隐（更长的可见时间）
    }

    fun setGlowColor(color: Int) {
        rippleColor = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
    }

    fun setLevel(newLevel: Float) {
        active = true
        val boosted = (newLevel * 3.0f).coerceIn(0f, 1f)

        smoothedLevel = if (boosted > smoothedLevel) {
            smoothedLevel + (boosted - smoothedLevel) * 0.5f
        } else {
            smoothedLevel + (boosted - smoothedLevel) * 0.1f
        }

        val now = System.currentTimeMillis()

        if (smoothedLevel > spawnThreshold
            && now - lastSpawnTime >= MIN_SPAWN_INTERVAL_MS
            && ripples.size < MAX_RIPPLES
        ) {
            ripples.add(Ripple(
                birthTime = now,
                intensity = smoothedLevel.coerceIn(0f, 1f)
            ))
            lastSpawnTime = now
        }

        invalidate()
    }

    fun reset() {
        active = false
        smoothedLevel = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (ripples.isEmpty() && !active) return

        val now = System.currentTimeMillis()
        ripples.removeAll { now - it.birthTime >= RIPPLE_LIFESPAN_MS }

        if (ripples.isEmpty() && !active) return

        val cx = width / 2f
        val cy = height / 2f
        val halfSize = minOf(cx, cy)
        val startR = halfSize * START_RADIUS_RATIO
        val endR = halfSize * END_RADIUS_RATIO

        val cr = Color.red(rippleColor)
        val cg = Color.green(rippleColor)
        val cb = Color.blue(rippleColor)

        for (ripple in ripples) {
            val age = (now - ripple.birthTime).toFloat()
            val t = (age / RIPPLE_LIFESPAN_MS).coerceIn(0f, 1f)

            // 缓出曲线：开始快后面慢
            val eased = 1f - (1f - t) * (1f - t)

            // 半径
            val radius = startR + (endR - startR) * eased

            // 线宽：粗 → 细，用缓出曲线让前段变化更快
            val strokeDp = MAX_STROKE_DP - (MAX_STROKE_DP - MIN_STROKE_DP) * eased
            val strokePx = strokeDp * density

            // 透明度：三段式 — 渐显 / 保持 / 渐隐
            val peakAlpha = 0.35f + ripple.intensity * 0.55f  // 0.35 ~ 0.9
            val alphaFraction = when {
                t < FADE_IN_FRACTION -> {
                    // 渐显
                    val fadeIn = t / FADE_IN_FRACTION
                    // 用平滑曲线淡入，避免突然出现
                    fadeIn * fadeIn * peakAlpha
                }
                t < FADE_OUT_START -> {
                    // 保持
                    peakAlpha
                }
                else -> {
                    // 渐隐
                    val fadeOut = (t - FADE_OUT_START) / (1f - FADE_OUT_START)
                    peakAlpha * (1f - fadeOut * fadeOut)  // 平滑淡出
                }
            }

            val alpha = (alphaFraction * 255).toInt().coerceIn(0, 255)
            if (alpha < 2) continue

            paint.color = Color.argb(alpha, cr, cg, cb)
            paint.strokeWidth = strokePx
            canvas.drawCircle(cx, cy, radius, paint)
        }

        if (ripples.isNotEmpty() || active) {
            postInvalidateOnAnimation()
        }
    }
}
