package com.trae.ridreceiver

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 北向朝上的雷达视图。
 * 有 GPS 时按"距离 + 方位"放置；无 GPS 时按 RSSI 估算半径、方位未知（空心点）。
 */
class RadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var drones: List<Drone> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var sweep = 0f

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 4200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            sweep = it.animatedValue as Float
            invalidate()
        }
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0E1828") }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.parseColor("#1D2C44")
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val sweepLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.parseColor("#22D3EE")
        alpha = 120
    }
    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10f)
        color = Color.parseColor("#5C7089")
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(9f)
        color = Color.parseColor("#D8E6F5")
        textAlign = Paint.Align.CENTER
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - dp(16f)
        if (radius <= 0f) return

        canvas.drawCircle(cx, cy, radius, bgPaint)

        for (i in 1..3) {
            canvas.drawCircle(cx, cy, radius * i / 3f, gridPaint)
        }
        canvas.drawLine(cx - radius, cy, cx + radius, cy, gridPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, gridPaint)

        val shader = SweepGradient(
            cx, cy,
            intArrayOf(Color.argb(110, 34, 211, 238), Color.argb(0, 34, 211, 238)),
            floatArrayOf(0f, 0.32f)
        )
        sweepPaint.shader = shader
        canvas.save()
        canvas.rotate(sweep, cx, cy)
        canvas.drawCircle(cx, cy, radius, sweepPaint)
        canvas.restore()

        val lineRad = Math.toRadians(sweep.toDouble())
        canvas.drawLine(
            cx, cy,
            cx + cos(lineRad).toFloat() * radius,
            cy + sin(lineRad).toFloat() * radius,
            sweepLine
        )

        // 方位标记
        canvas.drawText("北", cx, cy - radius - dp(4f), compassPaint)
        canvas.drawText("南", cx, cy + radius + dp(12f), compassPaint)
        canvas.drawText("西", cx - radius - dp(6f), cy + dp(4f), compassPaint)
        canvas.drawText("东", cx + radius + dp(6f), cy + dp(4f), compassPaint)

        val maxDistance = drones.mapNotNull { it.distanceM }.maxOrNull()
        val maxRange = max(300.0, (maxDistance ?: 300.0) * 1.15)
        val withFix = maxDistance != null

        if (!withFix && drones.isNotEmpty()) {
            canvas.drawText(
                "无 GPS · 仅信号强度",
                cx, cy + radius + dp(26f), compassPaint
            )
        }

        for (d in drones) {
            val angle: Double
            val ratio: Float
            if (d.distanceM != null) {
                angle = d.bearing ?: 0.0
                ratio = (d.distanceM!! / maxRange).toFloat().coerceIn(0.06f, 1f)
            } else {
                angle = (abs(d.key.hashCode()) % 360).toDouble()
                ratio = ((100 + d.rssi) / 60f).coerceIn(0.15f, 1f)
            }
            val rad = Math.toRadians(angle)
            val x = cx + sin(rad).toFloat() * radius * ratio
            val y = cy - cos(rad).toFloat() * radius * ratio
            val color = ContextCompat.getColor(context, d.statusColorRes())

            if (d.distanceM != null) {
                blipPaint.color = color
                blipPaint.alpha = 55
                canvas.drawCircle(x, y, dp(11f), blipPaint)
                blipPaint.alpha = 255
                canvas.drawCircle(x, y, dp(4f), blipPaint)
            } else {
                ringPaint.color = color
                canvas.drawCircle(x, y, dp(5f), ringPaint)
            }

            if (drones.size <= 8) {
                val dist = Format.distance(d.distanceM)
                val text = if (dist != null) dist else "${d.rssi}dBm"
                canvas.drawText(text, x, y - dp(10f), labelPaint)
            }
        }

        // 中心点
        blipPaint.color = Color.parseColor("#22D3EE")
        blipPaint.alpha = 220
        canvas.drawCircle(cx, cy, dp(2.5f), blipPaint)
        blipPaint.alpha = 255

        val scaleText = if (maxRange >= 1000) {
            String.format(java.util.Locale.US, "量程 %.1f km", maxRange / 1000.0)
        } else {
            "量程 ${maxRange.toInt()} m"
        }
        canvas.drawText(scaleText, cx, cy + radius + dp(14f), compassPaint)
    }
}
