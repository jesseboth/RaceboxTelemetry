package com.example.raceboxtelemetry.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

class GForceMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var gLat = 0f
    private var gLong = 0f
    private var maxGForce = 0f
    private val maxDisplayGForce = 2.0f

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DFFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22C55E")
        style = Paint.Style.FILL
        setShadowLayer(8f, 0f, 0f, Color.parseColor("#8822C55E"))
    }

    private val dotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA")
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFFFFFF")
        textSize = 28f
        textAlign = Paint.Align.CENTER
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // Enable shadow rendering
    }

    fun updateGForces(gLat: Float, gLong: Float) {
        this.gLat = gLat
        this.gLong = gLong

        // Update max G-force
        val totalG = sqrt(gLat * gLat + gLong * gLong)
        if (totalG > maxGForce) {
            maxGForce = totalG
        }

        // Update dot color based on intensity
        dotPaint.color = when {
            totalG > 1.5f -> Color.parseColor("#EF4444") // Red
            totalG > 0.8f -> Color.parseColor("#F59E0B") // Orange
            else -> Color.parseColor("#22C55E") // Green
        }

        invalidate()
    }

    fun resetMaxGForce() {
        maxGForce = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val centerX = width / 2
        val centerY = height / 2
        val radius = min(width, height) / 2 - 40f

        // Draw circle background
        canvas.drawCircle(centerX, centerY, radius, circlePaint)
        canvas.drawCircle(centerX, centerY, radius, borderPaint)

        // Draw crosshairs
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, crosshairPaint)
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, crosshairPaint)

        // Calculate dot position
        // Lateral: positive = right, negative = left
        // Longitudinal: positive = forward (up), negative = brake (down)
        val clampedGLat = gLat.coerceIn(-maxDisplayGForce, maxDisplayGForce)
        val clampedGLong = gLong.coerceIn(-maxDisplayGForce, maxDisplayGForce)

        val dotX = centerX + (clampedGLat / maxDisplayGForce) * radius
        val dotY = centerY - (clampedGLong / maxDisplayGForce) * radius // Invert Y axis

        // Draw dot
        val dotRadius = 12f
        canvas.drawCircle(dotX, dotY, dotRadius, dotPaint)
        canvas.drawCircle(dotX, dotY, dotRadius, dotBorderPaint)

        // Draw labels
        val labelY = centerY + radius + 35f
        canvas.drawText("-${maxDisplayGForce.toInt()}g", centerX - radius, labelY, textPaint)
        canvas.drawText("0", centerX, labelY, textPaint)
        canvas.drawText("+${maxDisplayGForce.toInt()}g", centerX + radius, labelY, textPaint)

        // Draw max G-force
        val maxGText = "Max: ${"%.1f".format(maxGForce)}g"
        canvas.drawText(maxGText, centerX, labelY + 30f, textPaint)

        // Draw title
        canvas.drawText("G-Force", centerX, centerY - radius - 15f, titlePaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredSize = 180
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val width = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> min(desiredSize, widthSize)
            else -> desiredSize
        }

        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> min(desiredSize, heightSize)
            else -> desiredSize
        }

        setMeasuredDimension(width, height)
    }
}
