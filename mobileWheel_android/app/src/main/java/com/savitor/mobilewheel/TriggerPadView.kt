package com.savitor.mobilewheel

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 扳机操控框：整个矩形就是可触控面积（不再是原来只有一条细轨道能点）。
 *
 * 触摸点越靠上，扳机压得越深（顶部 = 满值）；框内从底部往上按百分比填充，
 * 填充色随百分比变化（绿 → 黄 → 红），松手后回弹到 0，模拟手柄扳机的弹簧。
 *
 * 自绘而不用 SeekBar：既要去掉滑块，又要让整块面积参与命中，还要按百分比染色，
 * 用 SeekBar 反而要绕开它自己的 drawable 与命中逻辑。
 */
class TriggerPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 最大行程，与原来 SeekBar 的 max 保持一致 */
    var max = 255

    /** 当前行程 0..max */
    var progress = 0
        private set

    /** 行程变化回调（触摸与回弹动画都会触发） */
    var onProgressChanged: ((Int) -> Unit)? = null

    private var resetAnimator: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val cornerRadius = 10f * density

    private val padRect = RectF()
    private val fillRect = RectF()

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDEDED.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xFFBDBDBD.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val inset = borderPaint.strokeWidth / 2f
        padRect.set(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(padRect, cornerRadius, cornerRadius, backgroundPaint)
        canvas.drawRoundRect(padRect, cornerRadius, cornerRadius, borderPaint)

        val ratio = if (max > 0) progress.toFloat() / max else 0f
        if (ratio <= 0f) return

        // 从底部往上填充：矩形不够高时圆角要跟着收，否则填充块会凸出框外
        val fillHeight = (height - inset * 2) * ratio
        val radius = min(cornerRadius, min(fillHeight / 2f, (width - inset * 2) / 2f))
        fillRect.set(inset, height - inset - fillHeight, width - inset, height - inset)
        fillPaint.color = colorFor(ratio)
        canvas.drawRoundRect(fillRect, radius, radius, fillPaint)
    }

    /**
     * 按百分比取色：0 绿 → 0.5 黄 → 1 红。
     * 用浅色系（Material 400 一档）保证黑色数值文字和边框还看得清。
     */
    private fun colorFor(ratio: Float): Int {
        val t = ratio.coerceIn(0f, 1f)
        return if (t < 0.5f) {
            lerpColor(COLOR_LOW, COLOR_MID, t / 0.5f)
        } else {
            lerpColor(COLOR_MID, COLOR_HIGH, (t - 0.5f) / 0.5f)
        }
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * t).roundToInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * t).roundToInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).roundToInt()
        return Color.rgb(r, g, b)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.actionMasked) {
            // 按下去就立刻响应，不等移动到才变色
            MotionEvent.ACTION_DOWN -> {
                resetAnimator?.cancel()
                applyTouchY(event.y)
            }

            MotionEvent.ACTION_MOVE -> applyTouchY(event.y)

            MotionEvent.ACTION_UP -> {
                applyTouchY(event.y)
                animateBackToZero()
            }

            MotionEvent.ACTION_CANCEL -> animateBackToZero()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        resetAnimator?.cancel()
        resetAnimator = null
        super.onDetachedFromWindow()
    }

    /** 触摸点换算成行程：顶部为满值 */
    private fun applyTouchY(y: Float) {
        if (height <= 0) return

        val ratio = (1f - y / height).coerceIn(0f, 1f)
        val value = (ratio * max).roundToInt()
        if (value == progress) return

        progress = value
        invalidate()
        onProgressChanged?.invoke(value)
    }

    /** 松手后回到 0：扳机松开就是 0，框不会停在按下位置 */
    private fun animateBackToZero() {
        resetAnimator?.cancel()
        val from = progress
        if (from == 0) return

        resetAnimator = ValueAnimator.ofInt(from, 0).apply {
            duration = SPRING_BACK_MS
            addUpdateListener { animation ->
                progress = animation.animatedValue as Int
                invalidate()
                onProgressChanged?.invoke(progress)
            }
            start()
        }
    }

    private companion object {
        /** 回弹动画时长：短一些更接近真实扳机 */
        const val SPRING_BACK_MS = 120L

        const val COLOR_LOW = 0xFF66BB6A.toInt()  // 绿
        const val COLOR_MID = 0xFFFFCA28.toInt()  // 黄
        const val COLOR_HIGH = 0xFFEF5350.toInt() // 红
    }
}
