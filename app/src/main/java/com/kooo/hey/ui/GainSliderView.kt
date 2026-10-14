package com.kooo.hey.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * 垂直增益滑块，支持丝滑的触摸交互和动画效果
 *
 * 特性：
 * - 垂直滑动调节增益（1.0x ~ 15.0x）
 * - 触摸时放大显示当前值
 * - 松手后自动收缩
 * - 渐变色指示增益级别
 */
class GainSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class ValueMode { MULTIPLIER, PERCENT }
    var valueMode: ValueMode = ValueMode.MULTIPLIER
    var label: String = ""

    // 增益范围
    private var minGain = 1.0f
    private var maxGain = 30.0f
    private var currentGain = 5.0f

    /** true = 线性映射, false = 对数映射 */
    var linearMapping = false

    // 触摸状态
    private var isTouching = false
    private var touchStartY = 0f
    private var touchStartGain = 0f

    // 动画状态
    private var expandProgress = 0f  // 0=收缩, 1=展开
    private var expandAnimator: ValueAnimator? = null

    // 回调
    var onGainChanged: ((Float) -> Unit)? = null

    // 绘制对象
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        setShadowLayer(12f, 0f, 4f, Color.argb(80, 0, 0, 0))
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 48f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(8f, 0f, 2f, Color.argb(120, 0, 0, 0))
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = 34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // 渐变色（低增益=绿色，中增益=黄色，高增益=红色）
    private var gradientColors = intArrayOf(
        Color.rgb(76, 175, 80),   // 绿色
        Color.rgb(255, 235, 59),  // 黄色
        Color.rgb(255, 152, 0),   // 橙色
        Color.rgb(244, 67, 54)    // 红色
    )

    fun setGradientColors(colors: IntArray) {
        gradientColors = colors
        updateGradient()
        invalidate()
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)  // 启用阴影
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGradient()
    }

    private fun updateGradient() {
        if (height > 0) {
            val trackHeight = height - paddingTop - paddingBottom - 100f
            trackPaint.shader = LinearGradient(
                0f, paddingTop + 50f,
                0f, paddingTop + 50f + trackHeight,
                gradientColors,
                null,
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val trackHeight = height - paddingTop - paddingBottom - 160f
        val trackTop = paddingTop + 110f
        val trackBottom = trackTop + trackHeight

        // Draw top label (e.g. "🎤" or "🎵")
        if (label.isNotEmpty()) {
            canvas.drawText(label, centerX, paddingTop + 65f, hintPaint)
        }

        // 计算滑块位置（使用对数映射，感知均匀）
        val position = gainToPosition(currentGain)
        val thumbY = trackTop + trackHeight * (1f - position)

        // 绘制轨道（收缩/展开动画）
        val trackWidth = 12f + expandProgress * 8f
        val trackRadius = trackWidth / 2f
        val trackRect = RectF(
            centerX - trackRadius,
            trackTop,
            centerX + trackRadius,
            trackBottom
        )
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint)

        // 绘制滑块（圆形，触摸时放大）
        val thumbRadius = 28f + expandProgress * 12f
        canvas.drawCircle(centerX, thumbY, thumbRadius, thumbPaint)

        // 绘制增益值（触摸时显示，始终在滑块上方，但不超出视图）
        if (expandProgress > 0.1f) {
            val alpha = (expandProgress * 255).toInt().coerceIn(0, 255)
            textPaint.alpha = alpha

            val gainText = formatGainText()
            val textY = (thumbY - thumbRadius - 20f).coerceAtLeast(paddingTop + textPaint.textSize)
            canvas.drawText(gainText, centerX, textY, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isTouching = true
                touchStartY = event.y
                touchStartGain = currentGain
                animateExpand(true)
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTouching) {
                    val trackHeight = height - paddingTop - paddingBottom - 100f
                    val deltaY = touchStartY - event.y  // 上滑为正
                    val deltaPos = deltaY / trackHeight
                    val startPos = gainToPosition(touchStartGain)
                    val newPos = (startPos + deltaPos).coerceIn(0f, 1f)
                    val newGain = positionToGain(newPos)
                    if (abs(newGain - currentGain) > 0.01f) {
                        currentGain = newGain
                        onGainChanged?.invoke(currentGain)
                        invalidate()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isTouching = false
                animateExpand(false)
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun animateExpand(expand: Boolean) {
        expandAnimator?.cancel()
        expandAnimator = ValueAnimator.ofFloat(expandProgress, if (expand) 1f else 0f).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                expandProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * 设置当前增益值
     */
    fun setGain(gain: Float) {
        currentGain = gain.coerceIn(minGain, maxGain)
        invalidate()
    }

    /**
     * 获取当前增益值
     */
    fun getGain(): Float = currentGain

    /**
     * 设置增益范围
     */
    fun setGainRange(min: Float, max: Float) {
        minGain = min
        maxGain = max
        currentGain = currentGain.coerceIn(minGain, maxGain)
        invalidate()
    }

    /**
     * 丝滑地切换到新的增益范围和值
     */
    private var switchAnimator: ValueAnimator? = null

    fun animateToConfig(newMin: Float, newMax: Float, newGain: Float, durationMs: Long = 400, linear: Boolean = linearMapping) {
        linearMapping = linear
        switchAnimator?.cancel()
        val startMin = minGain
        val startMax = maxGain
        val startGain = currentGain
        switchAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                minGain = startMin + (newMin - startMin) * t
                maxGain = startMax + (newMax - startMax) * t
                currentGain = (startGain + (newGain - startGain) * t).coerceIn(minGain, maxGain)
                updateGradient()
                invalidate()
            }
            start()
        }
    }

    /**
     * 格式化显示值（百分比模式或增益倍数模式）
     */
    private fun formatGainText(): String {
        return when (valueMode) {
            ValueMode.PERCENT -> {
                val pct = if (maxGain <= minGain) 0 else {
                    ((currentGain - minGain) / (maxGain - minGain) * 100).toInt().coerceIn(0, 100)
                }
                "$pct%"
            }
            ValueMode.MULTIPLIER -> {
                if (maxGain <= 5f) {
                    String.format("%.1fx", currentGain)
                } else {
                    "${currentGain.toInt()}x"
                }
            }
        }
    }

    /** 增益值 → 滑块位置 (0~1) */
    private fun gainToPosition(gain: Float): Float {
        if (maxGain <= minGain) return 0f
        val g = gain.coerceIn(minGain, maxGain)
        return if (linearMapping) {
            (g - minGain) / (maxGain - minGain)
        } else {
            if (minGain <= 0f) return 0f
            ln(g / minGain) / ln(maxGain / minGain)
        }
    }

    /** 滑块位置 (0~1) → 增益值 */
    private fun positionToGain(position: Float): Float {
        val p = position.coerceIn(0f, 1f)
        return if (linearMapping) {
            minGain + (maxGain - minGain) * p
        } else {
            minGain * (maxGain / minGain).pow(p)
        }
    }
}
