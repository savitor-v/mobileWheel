package com.savitor.mobilewheel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * 横向水平仪（人工地平仪样式）：
 * 前景是固定在屏幕上的竖直向上指针（代表机身方向），
 * 背景是随倾角反向旋转的地平线，始终与真实地平线保持平行。
 * 指针与地平线之间的夹角即可直观看出横向倾角。
 *
 * 角度为正表示屏幕左侧偏低（向左倾斜），此时机身逆时针偏转，
 * 地平线在屏幕上顺时针旋转以保持与真实地平线平行。
 */
class LevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val windowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#263238")
    }

    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 28f * scaledDensity
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 15f * scaledDensity
    }

    private val windowPath = Path()
    private val pointerPath = Path()

    private var angle = 0f
    private var isActive = false

    /**
     * 更新水平仪读数。
     *
     * @param tilt 横向倾角（度），正值为屏幕左侧偏低
     * @param active 是否处于监测状态
     */
    fun update(tilt: Float, active: Boolean) {
        angle = tilt
        isActive = active
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val pad = 6f * density
        val window = RectF(pad, pad, w - pad, h * 0.72f)
        val radius = 14f * density
        val cx = window.centerX()
        val cy = window.centerY()
        val r = min(window.width(), window.height()) / 2f

        // 底盘
        canvas.drawRoundRect(window, radius, radius, windowPaint)

        val leveled = isActive && abs(angle) <= LEVEL_TOLERANCE_DEG
        groundPaint.color = when {
            !isActive -> Color.parseColor("#37474F")
            leveled -> Color.parseColor("#2E7D32")
            else -> Color.parseColor("#33691E")
        }
        horizonPaint.color = if (isActive) Color.parseColor("#ECEFF1") else Color.parseColor("#607D8B")

        // 背景：地平线反向旋转，保持与真实地平线平行
        windowPath.reset()
        windowPath.addRoundRect(window, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(windowPath)
        canvas.rotate(angle, cx, cy)
        // 地面（地平线以下部分）
        canvas.drawRect(cx - 3f * w, cy, cx + 3f * w, cy + 3f * h, groundPaint)
        // 地平线
        canvas.drawLine(cx - 3f * w, cy, cx + 3f * w, cy, horizonPaint)
        canvas.restore()

        // 前景：固定在屏幕上的竖直向上指针
        pointerPaint.color = when {
            !isActive -> Color.parseColor("#90A4AE")
            leveled -> Color.parseColor("#66BB6A")
            else -> Color.parseColor("#FFA726")
        }
        val tipY = cy - r * 0.82f
        val baseY = cy + r * 0.12f
        val halfBase = r * 0.15f
        pointerPath.reset()
        pointerPath.moveTo(cx, tipY)
        pointerPath.lineTo(cx - halfBase, baseY)
        pointerPath.lineTo(cx + halfBase, baseY)
        pointerPath.close()
        canvas.drawPath(pointerPath, pointerPaint)
        canvas.drawCircle(cx, cy, 3.5f * density, pointerPaint)

        // 读数与方向
        valuePaint.color = pointerPaint.color
        labelPaint.color = pointerPaint.color

        val bottomSpace = h - window.bottom
        val valueY = window.bottom + bottomSpace * 0.45f
        val labelY = window.bottom + bottomSpace * 0.85f

        canvas.drawText(
            if (isActive) String.format(Locale.getDefault(), "%.1f°", angle) else "--",
            cx,
            valueY,
            valuePaint
        )
        canvas.drawText(directionText(), cx, labelY, labelPaint)
    }

    private fun directionText(): String = when {
        !isActive -> "未开始"
        abs(angle) <= LEVEL_TOLERANCE_DEG -> "水平"
        angle > 0 -> "向左倾斜"
        else -> "向右倾斜"
    }

    companion object {
        /** 判定为“水平”的角度阈值 */
        private const val LEVEL_TOLERANCE_DEG = 0.5f
    }
}