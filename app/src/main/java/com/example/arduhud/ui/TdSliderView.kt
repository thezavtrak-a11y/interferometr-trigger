package com.example.arduhud.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/** TouchDesigner-style dual-tone strip slider with in-track label. */
class TdSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var progress: Int = 0
        set(value) {
            val next = value.coerceIn(0, max)
            if (field != next) {
                field = next
                invalidate()
            }
        }

    var max: Int = 100
        set(value) {
            field = max(1, value)
            progress = progress.coerceIn(0, field)
            invalidate()
        }

    var fillColor: Int = Color.parseColor("#00ADEF")
        set(value) {
            field = value
            invalidate()
        }

    var trackColor: Int = Color.parseColor("#1F1F1F")
        set(value) {
            field = value
            invalidate()
        }

    var cursorColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    /** Drawn inside the strip; splits color at the thumb for contrast. */
    var labelText: String = ""
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var labelColorOnTrack: Int = Color.parseColor("#E0E0E0")
        set(value) {
            field = value
            invalidate()
        }

    var labelColorOnFill: Int = Color.parseColor("#1A1A1A")
        set(value) {
            field = value
            invalidate()
        }

    private var listener: ((progress: Int, fromUser: Boolean) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.parseColor("#55FFFFFF")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        color = labelColorOnTrack
    }
    private val rect = RectF()

    fun setOnProgressChangeListener(listener: ((progress: Int, fromUser: Boolean) -> Unit)?) {
        this.listener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minH = dp(32f).toInt()
        val h = resolveSize(minH, heightMeasureSpec)
        val w = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        setMeasuredDimension(w, max(h, minH))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = dp(2f)
        val left = pad
        val right = width - pad
        val top = height * 0.18f
        val bottom = height * 0.82f
        rect.set(left, top, right, bottom)

        // Sharp Metro strip (no rounded corners).
        trackPaint.color = trackColor
        canvas.drawRect(rect, trackPaint)

        val ratio = if (max <= 0) 0f else progress.toFloat() / max
        val fillRight = left + (right - left) * ratio
        if (fillRight > left) {
            fillPaint.color = fillColor
            rect.set(left, top, fillRight, bottom)
            canvas.drawRect(rect, fillPaint)
        }

        borderPaint.color = Color.parseColor("#3C3C3C")
        rect.set(left, top, right, bottom)
        canvas.drawRect(rect, borderPaint)

        if (labelText.isNotEmpty()) {
            labelPaint.textSize = sp(11f)
            val textX = left + dp(8f)
            val fm = labelPaint.fontMetrics
            val textY = (top + bottom) / 2f - (fm.ascent + fm.descent) / 2f

            // Unfilled / track side — light text
            labelPaint.color = labelColorOnTrack
            canvas.drawText(labelText, textX, textY, labelPaint)

            // Filled side — dark text (readable on orange/green)
            if (fillRight > left + 1f) {
                canvas.save()
                canvas.clipRect(left, top, fillRight, bottom)
                labelPaint.color = labelColorOnFill
                canvas.drawText(labelText, textX, textY, labelPaint)
                canvas.restore()
            }
        }

        val cursorX = fillRight.coerceIn(left, right)
        cursorPaint.color = cursorColor
        val cw = dp(2.5f)
        canvas.drawRect(cursorX - cw / 2f, top - dp(3f), cursorX + cw / 2f, bottom + dp(3f), cursorPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromX(event.x, fromUser = true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromX(x: Float, fromUser: Boolean) {
        val pad = dp(2f)
        val left = pad
        val right = width - pad
        val ratio = ((x - left) / (right - left)).coerceIn(0f, 1f)
        val next = (ratio * max).toInt().coerceIn(0, max)
        if (next != progress) {
            progress = next
            listener?.invoke(progress, fromUser)
        } else if (fromUser) {
            listener?.invoke(progress, true)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
