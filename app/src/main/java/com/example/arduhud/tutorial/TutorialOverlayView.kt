package com.example.arduhud.tutorial

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.example.arduhud.R
import com.google.android.material.button.MaterialButton
import kotlin.math.max

class TutorialOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val hole = RectF()
    private val overlayLoc = IntArray(2)
    private val targetLoc = IntArray(2)
    private val anchorLoc = IntArray(2)
    private val arrowPath = Path()
    private val holePad: Float
    private val holeRadius: Float
    private val gapPx: Int

    private val dimPaint = Paint().apply { color = 0xB3000000.toInt() }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF00ADEF.toInt()
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF00ADEF.toInt()
    }

    private var target: View? = null
    private var bottomAnchor: View? = null
    private var useGridHole = false
    private var hasHole = false
    private var extraPass: View? = null
    private val extraLoc = IntArray(2)
    private val extraHole = RectF()
    private var hasExtraPass = false
    var cardSlot: TutorialCardSlot = TutorialCardSlot.Bottom
    var onHoleTouch: (() -> Unit)? = null

    val card: View
    private val titleView: TextView
    private val bodyView: TextView
    private val progressView: TextView
    val backButton: MaterialButton
    val nextButton: MaterialButton
    val finishButton: MaterialButton

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false
        inflate(context, R.layout.overlay_tutorial, this)
        card = findViewById(R.id.tutorialCard)
        titleView = findViewById(R.id.tutorialTitle)
        bodyView = findViewById(R.id.tutorialBody)
        progressView = findViewById(R.id.tutorialProgress)
        backButton = findViewById(R.id.tutorialBackButton)
        nextButton = findViewById(R.id.tutorialNextButton)
        finishButton = findViewById(R.id.tutorialFinishButton)
        val d = resources.displayMetrics.density
        holePad = 8f * d
        holeRadius = 4f * d
        gapPx = (12f * d).toInt()
        strokePaint.strokeWidth = 2.5f * d
    }

    fun bindCopy(index: Int, total: Int, title: CharSequence, body: CharSequence) {
        progressView.text = context.getString(R.string.tutorial_progress, index + 1, total)
        titleView.text = title
        bodyView.text = body
        backButton.isEnabled = index > 0
        backButton.alpha = if (index > 0) 1f else 0.4f
        nextButton.visibility = if (index >= total - 1) GONE else VISIBLE
    }

    fun highlight(
        view: View?,
        grid: Boolean = false,
        bottomAnchor: View? = null,
        extraPassThrough: View? = null,
    ) {
        target = view
        this.bottomAnchor = bottomAnchor
        extraPass = extraPassThrough
        useGridHole = grid
        hasHole = false
        hasExtraPass = false
        positionCard()
        invalidate()
        post {
            positionCard()
            invalidate()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        updateHole()
        val x = ev.x
        val y = ev.y
        if (isOnCard(x, y)) {
            return super.dispatchTouchEvent(ev)
        }
        if (isOnPassThrough(x, y)) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                onHoleTouch?.invoke()
            }
            return false
        }
        return true
    }

    private fun isOnCard(x: Float, y: Float): Boolean {
        if (!card.isShown) return false
        return x >= card.left && x < card.right && y >= card.top && y < card.bottom
    }

    private fun isOnPassThrough(x: Float, y: Float): Boolean {
        if (hasHole && hole.contains(x, y)) return true
        if (hasExtraPass && extraHole.contains(x, y)) return true
        return false
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateHole()
    }

    override fun dispatchDraw(canvas: Canvas) {
        positionCard()
        updateHole()
        val sc = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        if (hasHole) {
            canvas.drawRoundRect(hole, holeRadius, holeRadius, clearPaint)
        }
        if (hasExtraPass) {
            canvas.drawRoundRect(extraHole, holeRadius, holeRadius, clearPaint)
        }
        canvas.restoreToCount(sc)
        if (hasHole) {
            canvas.drawRoundRect(hole, holeRadius, holeRadius, strokePaint)
            drawArrow(canvas)
        }
        super.dispatchDraw(canvas)
    }

    private fun updateHole() {
        hasHole = false
        val v = target ?: return
        if (!v.isShown || v.width <= 0 || v.height <= 0) return
        getLocationOnScreen(overlayLoc)
        v.getLocationOnScreen(targetLoc)
        val wfLeft = targetLoc[0] - overlayLoc[0] - holePad
        val wfTop = targetLoc[1] - overlayLoc[1] - holePad
        val wfRight = wfLeft + v.width + holePad * 2
        val wfBottom = wfTop + v.height + holePad * 2

        if (useGridHole) {
            val belowCard = if (cardSlot == TutorialCardSlot.Top) {
                card.bottom + gapPx.toFloat()
            } else {
                wfTop
            }
            var top = max(wfTop, belowCard)
            var bottom = wfBottom
            val anchor = bottomAnchor
            if (anchor != null && anchor.isShown) {
                anchor.getLocationOnScreen(anchorLoc)
                val anchorTop = (anchorLoc[1] - overlayLoc[1]).toFloat()
                if (anchorTop > top + 24f) bottom = minOf(bottom, anchorTop - holePad)
            }
            if (cardSlot == TutorialCardSlot.Bottom) {
                bottom = minOf(bottom, card.top - gapPx.toFloat())
            }
            hole.set(
                wfLeft.coerceAtLeast(4f),
                top.coerceAtLeast(4f),
                wfRight.coerceAtMost(width - 4f),
                bottom.coerceAtMost(height - 4f),
            )
        } else {
            hole.set(
                wfLeft.coerceAtLeast(4f),
                wfTop.coerceAtLeast(4f),
                wfRight.coerceAtMost(width - 4f),
                wfBottom.coerceAtMost(height - 4f),
            )
        }
        hasHole = hole.width() > 8f && hole.height() > 8f
        hasExtraPass = false
        val extra = extraPass
        if (extra != null && extra.isShown && extra.width > 0 && extra.height > 0) {
            extra.getLocationOnScreen(extraLoc)
            val l = extraLoc[0] - overlayLoc[0] - holePad
            var t = extraLoc[1] - overlayLoc[1] - holePad
            val r = l + extra.width + holePad * 2
            val b = t + extra.height + holePad * 2
            if (hasHole && cardSlot == TutorialCardSlot.BelowHole) {
                t = t.coerceAtLeast(hole.bottom)
            }
            extraHole.set(
                l.coerceAtLeast(4f),
                t.coerceAtLeast(4f),
                r.coerceAtMost(width - 4f),
                b.coerceAtMost(height - 4f),
            )
            hasExtraPass = extraHole.width() > 8f && extraHole.height() > 8f
        }
    }

    private fun positionCard() {
        if (width == 0 || height == 0) return
        if (cardSlot == TutorialCardSlot.BelowHole) {
            updateHole()
        }
        card.measure(
            MeasureSpec.makeMeasureSpec(width - paddingLeft - paddingRight, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST),
        )
        val cardH = card.measuredHeight
        val params = card.layoutParams as LayoutParams
        val maxTop = (height - cardH - gapPx).coerceAtLeast(gapPx)
        val desired = when (cardSlot) {
            TutorialCardSlot.Top -> gapPx
            TutorialCardSlot.Bottom -> maxTop
            TutorialCardSlot.BelowHole -> {
                if (hasHole) {
                    (hole.bottom.toInt() + gapPx).coerceIn(gapPx, maxTop)
                } else {
                    maxTop
                }
            }
        }
        if (params.topMargin != desired || params.gravity != (Gravity.TOP or Gravity.CENTER_HORIZONTAL)) {
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.width = LayoutParams.MATCH_PARENT
            params.topMargin = desired
            card.layoutParams = params
        }
    }

    private fun drawArrow(canvas: Canvas) {
        val cx = hole.centerX()
        val size = 10f * resources.displayMetrics.density
        arrowPath.reset()
        if (cardSlot == TutorialCardSlot.Top) {
            val y = hole.top
            arrowPath.moveTo(cx, y)
            arrowPath.lineTo(cx - size, y - size)
            arrowPath.lineTo(cx + size, y - size)
        } else {
            val y = hole.bottom
            arrowPath.moveTo(cx, y)
            arrowPath.lineTo(cx - size, y + size)
            arrowPath.lineTo(cx + size, y + size)
        }
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowPaint)
    }
}
