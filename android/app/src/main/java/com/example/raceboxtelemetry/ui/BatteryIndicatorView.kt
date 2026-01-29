package com.example.raceboxtelemetry.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.raceboxtelemetry.R

class BatteryIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var batteryLevel = 0 // 0-100
    private var isCharging = false

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#AAAAAA")
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // Use the app's primary blue color
        color = Color.parseColor("#2196F3")
    }

    private val chargingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4CAF50") // Green when charging
    }

    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#AAAAAA")
    }

    fun setBatteryLevel(level: Int, charging: Boolean = false) {
        batteryLevel = level.coerceIn(0, 100)
        isCharging = charging
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        // Battery dimensions
        val tipWidth = height * 0.15f
        val bodyWidth = width - tipWidth - 4f
        val bodyHeight = height * 0.8f
        val bodyTop = (height - bodyHeight) / 2f
        val bodyLeft = 2f
        val cornerRadius = 2f

        // Draw battery body (rounded rectangle)
        val bodyRect = RectF(bodyLeft, bodyTop, bodyLeft + bodyWidth, bodyTop + bodyHeight)
        canvas.drawRoundRect(bodyRect, cornerRadius, cornerRadius, bodyPaint)

        // Draw battery tip (small rectangle on the right)
        val tipLeft = bodyLeft + bodyWidth
        val tipTop = height * 0.35f
        val tipBottom = height * 0.65f
        val tipRect = RectF(tipLeft, tipTop, tipLeft + tipWidth, tipBottom)
        canvas.drawRoundRect(tipRect, 1f, 1f, tipPaint)

        // Draw battery fill based on level
        if (batteryLevel > 0) {
            val fillWidth = (bodyWidth - 4f) * (batteryLevel / 100f)
            val fillRect = RectF(
                bodyLeft + 2f,
                bodyTop + 2f,
                bodyLeft + 2f + fillWidth,
                bodyTop + bodyHeight - 2f
            )

            // Use green if charging, blue otherwise
            val paint = if (isCharging) chargingPaint else fillPaint
            canvas.drawRoundRect(fillRect, 1f, 1f, paint)
        }

        // Draw charging indicator (lightning bolt) if charging
        if (isCharging) {
            drawChargingBolt(canvas, bodyLeft, bodyTop, bodyWidth, bodyHeight)
        }
    }

    private fun drawChargingBolt(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        val path = Path()
        val centerX = left + width / 2f
        val centerY = top + height / 2f
        val boltWidth = width * 0.2f
        val boltHeight = height * 0.5f

        // Lightning bolt shape
        path.moveTo(centerX + boltWidth * 0.2f, centerY - boltHeight / 2f)
        path.lineTo(centerX - boltWidth * 0.3f, centerY)
        path.lineTo(centerX, centerY)
        path.lineTo(centerX - boltWidth * 0.2f, centerY + boltHeight / 2f)
        path.lineTo(centerX + boltWidth * 0.3f, centerY)
        path.lineTo(centerX, centerY)
        path.close()

        val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        canvas.drawPath(path, boltPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = 28
        val desiredHeight = 12

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val width = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> minOf(desiredWidth, widthSize)
            else -> desiredWidth
        }

        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> minOf(desiredHeight, heightSize)
            else -> desiredHeight
        }

        setMeasuredDimension(width, height)
    }
}
