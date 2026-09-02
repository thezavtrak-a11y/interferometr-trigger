package com.example.arduhud.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.example.arduhud.sensors.SensorChannel
import com.example.arduhud.sensors.SensorProcessor
import com.example.arduhud.sensors.TimeWindowSec
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class MotionWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    data class Sample(
        val timestampNs: Long,
        val values: Map<SensorChannel, Float>,
        val activity: Float,
    )

    private val samples = ArrayDeque<Sample>(MAX_SAMPLES)
    private val clickMarkersNs = ArrayDeque<Long>(64)

    private var threshold = SensorProcessor.DEFAULT_THRESHOLD
    private var timeWindowSec = TimeWindowSec.S20.seconds.toFloat()
    private var enabledChannels: Set<SensorChannel> = setOf(
        SensorChannel.ACC_MAG,
        SensorChannel.GYRO_MAG,
    )
    private var showSum = true
    private var autoscale = true
    private var manualYMax = 5f
    /** EMA τ for drawn series (0 = raw). Detect uses the same τ in SensorProcessor. */
    private var smoothTauSec = 0f

    private var timingOverlay = false
    private var paused = false
    private var pauseNewestNs = 0L
    private var panOffsetNs = 0L
    private var minMotionSec = SensorProcessor.DEFAULT_MIN_MOTION_SEC
    private var minRestSec = SensorProcessor.DEFAULT_MIN_REST_SEC
    private var motionGateEnabled = false
    private var restGateEnabled = false

    private var draggingThreshold = false
    private var panningWaveform = false
    private var lastPanX = 0f
    private var scaleAxis = SCALE_AXIS_UNSET
    private var thresholdListener: ((Float) -> Unit)? = null
    private var yZoomListener: ((autoscale: Boolean, yMax: Float) -> Unit)? = null
    private var timeWindowListener: ((Float) -> Unit)? = null
    private var touchDownListener: (() -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaleAxis = SCALE_AXIS_UNSET
                panningWaveform = false
                draggingThreshold = false
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val dSpanX = abs(detector.currentSpanX - detector.previousSpanX)
                val dSpanY = abs(detector.currentSpanY - detector.previousSpanY)
                if (scaleAxis == SCALE_AXIS_UNSET) {
                    if (dSpanX < 2f && dSpanY < 2f) return false
                    // Prefer Y when close — pure vertical pinch often has comparable spanX noise.
                    scaleAxis = if (dSpanY * 1.25f >= dSpanX) SCALE_AXIS_Y else SCALE_AXIS_TIME
                }
                return when (scaleAxis) {
                    SCALE_AXIS_Y -> {
                        // Vertical span ratio tracks amplitude pinch better than diagonal scaleFactor.
                        val prevY = detector.previousSpanY.coerceAtLeast(1f)
                        val factor = (detector.currentSpanY / prevY).coerceIn(0.85f, 1.15f)
                        applyYPinch(factor)
                    }
                    else -> applyTimePinch(detector.scaleFactor)
                }
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                scaleAxis = SCALE_AXIS_UNSET
            }
        },
    )

    init {
        isClickable = true
        isFocusable = true
    }

    private val bgPaint = Paint().apply { color = Color.BLACK }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B2B2B")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF7043")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val thresholdTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFAB91")
        textSize = sp(12f)
        textAlign = Paint.Align.RIGHT
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEE58")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val markerAgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEE58")
        textSize = sp(11f)
        textAlign = Paint.Align.LEFT
    }
    private val markerIntervalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80D8FF")
        textSize = sp(11f)
        textAlign = Paint.Align.LEFT
    }
    private val motionWindowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64B5F6")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val restWindowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#81C784")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val timingLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }
    private val sumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
    }
    private val peakDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val peakDotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.BLACK
    }
    private val peakTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        textAlign = Paint.Align.LEFT
        color = Color.WHITE
        isFakeBoldText = true
        setShadowLayer(2.5f, 0f, 0f, Color.BLACK)
    }
    private val seriesPath = Path()
    private val peakTsBuf = ArrayList<Long>(256)
    private val peakValBuf = ArrayList<Float>(256)

    private val channelPaints = SensorChannel.entries.associateWith { stroke(it.colorHex) }

    fun setOnThresholdChangeListener(listener: ((Float) -> Unit)?) {
        thresholdListener = listener
    }

    fun setOnTouchDownListener(listener: (() -> Unit)?) {
        touchDownListener = listener
    }

    fun setOnYZoomChangeListener(listener: ((autoscale: Boolean, yMax: Float) -> Unit)?) {
        yZoomListener = listener
    }

    fun setOnTimeWindowChangeListener(listener: ((Float) -> Unit)?) {
        timeWindowListener = listener
    }

    fun setThreshold(value: Float) {
        threshold = value.coerceIn(SensorProcessor.MIN_THRESHOLD, SensorProcessor.MAX_THRESHOLD)
        invalidate()
    }

    fun getThreshold(): Float = threshold

    fun visibleYMax(): Float = currentYMax()

    fun setTimeWindow(window: TimeWindowSec) {
        setTimeWindowSec(window.seconds.toFloat(), notify = false)
    }

    fun setTimeWindowSec(seconds: Float, notify: Boolean = true) {
        timeWindowSec = seconds.coerceIn(TIME_WINDOW_MIN_SEC, TIME_WINDOW_MAX_SEC)
        clampPanOffset()
        trimHistory()
        if (notify) timeWindowListener?.invoke(timeWindowSec)
        invalidate()
    }

    fun getTimeWindowSec(): Float = timeWindowSec

    fun setEnabledChannels(channels: Set<SensorChannel>) {
        enabledChannels = channels
        invalidate()
    }

    fun setShowSum(enabled: Boolean) {
        showSum = enabled
        invalidate()
    }

    fun setSmoothTauSec(seconds: Float) {
        smoothTauSec = seconds.coerceIn(0f, SMOOTH_TAU_MAX_SEC)
        invalidate()
    }

    fun getSmoothTauSec(): Float = smoothTauSec

    fun setAutoscale(enabled: Boolean) {
        autoscale = enabled
        invalidate()
    }

    fun isAutoscale(): Boolean = autoscale

    fun setManualYMax(value: Float) {
        manualYMax = value.coerceIn(Y_ZOOM_MIN, Y_ZOOM_MAX)
        invalidate()
    }

    fun getManualYMax(): Float = manualYMax

    fun setTimingOverlay(
        enabled: Boolean,
        minMotionSec: Float,
        minRestSec: Float,
        motionGateEnabled: Boolean,
        restGateEnabled: Boolean,
    ) {
        timingOverlay = enabled
        this.minMotionSec = minMotionSec
        this.minRestSec = minRestSec
        this.motionGateEnabled = motionGateEnabled
        this.restGateEnabled = restGateEnabled
        invalidate()
    }

    fun setPaused(enabled: Boolean) {
        paused = enabled
        if (enabled) {
            pauseNewestNs = samples.lastOrNull()?.timestampNs ?: 0L
            panOffsetNs = 0L
        } else {
            pauseNewestNs = 0L
            panOffsetNs = 0L
            panningWaveform = false
        }
        invalidate()
    }

    fun isPaused(): Boolean = paused

    fun clickMarkerTimestamps(): List<Long> = clickMarkersNs.toList()

    fun newestSampleNs(): Long = samples.lastOrNull()?.timestampNs ?: 0L

    fun panToTimestamp(ns: Long) {
        if (!paused || pauseNewestNs <= 0L || ns <= 0L) return
        panOffsetNs = pauseNewestNs - ns - windowNs() / 2L
        clampPanOffset()
        invalidate()
    }

    fun clearSamples() {
        samples.clear()
        clickMarkersNs.clear()
        invalidate()
    }

    fun addSample(
        timestampNs: Long,
        values: Map<SensorChannel, Float>,
        activity: Float,
        clickMarkerNs: Long?,
    ) {
        // Always collect; while paused the view stays on pauseNewest ± pan.
        samples.addLast(Sample(timestampNs, values, activity))
        if (clickMarkerNs != null) {
            clickMarkersNs.addLast(clickMarkerNs)
        }
        trimHistory()
        if (!paused) invalidate()
    }

    private fun applyYPinch(rawFactor: Float): Boolean {
        val factor = rawFactor.coerceIn(0.85f, 1.15f)
        if (abs(factor - 1f) < 0.01f) return false
        val current = if (autoscale) currentYMax() else manualYMax
        val next = (current / factor).coerceIn(Y_ZOOM_MIN, Y_ZOOM_MAX)
        if (next >= Y_ZOOM_MAX - 0.05f) {
            autoscale = true
            manualYMax = Y_ZOOM_MAX
        } else {
            autoscale = false
            manualYMax = next
        }
        yZoomListener?.invoke(autoscale, manualYMax)
        invalidate()
        return true
    }

    private fun applyTimePinch(rawFactor: Float): Boolean {
        val factor = rawFactor.coerceIn(0.85f, 1.15f)
        if (abs(factor - 1f) < 0.01f) return false
        // Pinch out → zoom in → narrower time window.
        setTimeWindowSec(timeWindowSec / factor, notify = true)
        return true
    }

    private fun windowNs(): Long = (timeWindowSec * 1_000_000_000f).toLong()

    private fun viewNewestNs(): Long {
        if (!paused || pauseNewestNs <= 0L) {
            return samples.lastOrNull()?.timestampNs ?: 0L
        }
        return pauseNewestNs - panOffsetNs
    }

    private fun ageReferenceNs(): Long {
        return if (paused && pauseNewestNs > 0L) pauseNewestNs else viewNewestNs()
    }

    private fun clampPanOffset() {
        if (!paused || pauseNewestNs <= 0L || samples.isEmpty()) {
            panOffsetNs = 0L
            return
        }
        val first = samples.first().timestampNs
        val maxOffset = (pauseNewestNs - windowNs() - first).coerceAtLeast(0L)
        panOffsetNs = panOffsetNs.coerceIn(0L, maxOffset)
    }

    private fun trimHistory() {
        val newest = samples.lastOrNull()?.timestampNs ?: return
        val retainNs = (RETAIN_HISTORY_SEC * 1_000_000_000f).toLong()
        val pauseRetain = if (paused && pauseNewestNs > 0L) {
            pauseNewestNs - retainNs
        } else {
            Long.MAX_VALUE
        }
        val cutoff = min(newest - retainNs, pauseRetain)
        while (samples.isNotEmpty() && samples.first().timestampNs < cutoff) {
            samples.removeFirst()
        }
        while (clickMarkersNs.isNotEmpty() && clickMarkersNs.first() < cutoff) {
            clickMarkersNs.removeFirst()
        }
        while (samples.size > MAX_SAMPLES) {
            samples.removeFirst()
        }
        if (paused) clampPanOffset()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Always claim the stream so 2-finger pinch works even when not paused.
        // (Returning false from ACTION_DOWN drops later POINTER_DOWN / scale events.)
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress || event.pointerCount >= 2) {
            draggingThreshold = false
            panningWaveform = false
            return true
        }

        val h = height.toFloat()
        val w = width.toFloat()
        if (h <= 0f || w <= 0f) return true
        val yMax = currentYMax()
        val thresholdY = valueToY(threshold, h, yMax)
        val touchSlop = dp(28f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownListener?.invoke()
                draggingThreshold = false
                panningWaveform = false
                if (abs(event.y - thresholdY) <= touchSlop) {
                    draggingThreshold = true
                    updateThresholdFromY(event.y, h, yMax)
                } else if (paused) {
                    panningWaveform = true
                    lastPanX = event.x
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingThreshold) {
                    updateThresholdFromY(event.y, h, yMax)
                } else if (panningWaveform && paused) {
                    val dx = event.x - lastPanX
                    lastPanX = event.x
                    // Finger right → waveform follows → look further into the past.
                    panOffsetNs += ((dx / w) * windowNs()).toLong()
                    clampPanOffset()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingThreshold = false
                panningWaveform = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun updateThresholdFromY(y: Float, h: Float, yMax: Float) {
        val normalized = (1f - (y / h).coerceIn(0f, 1f))
        val value = (normalized * yMax).coerceIn(
            SensorProcessor.MIN_THRESHOLD,
            SensorProcessor.MAX_THRESHOLD,
        )
        threshold = value
        thresholdListener?.invoke(value)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val drawSamples = samples.toList()
        val drawClicks = clickMarkersNs.toList()
        val yMax = currentYMax(drawSamples)
        drawHorizontalGrid(canvas, w, h)

        if (drawSamples.size < 2) {
            drawThreshold(canvas, w, h, yMax)
            return
        }

        val newest = viewNewestNs()
        val windowNs = windowNs()
        val oldest = newest - windowNs
        val ageRefNs = ageReferenceNs()

        drawVerticalGrid(canvas, w, h, oldest, newest, windowNs)

        for (channel in enabledChannels) {
            val paint = channelPaints[channel] ?: continue
            drawSeries(drawSamples, w, h, yMax, oldest, windowNs) { it.values[channel] ?: 0f }
                .also { canvas.drawPath(it, paint) }
        }

        if (showSum && enabledChannels.size > 1) {
            drawSeries(drawSamples, w, h, yMax, oldest, windowNs) { sample ->
                enabledChannels.sumOf { (sample.values[it] ?: 0f).toDouble() }.toFloat()
            }.also { canvas.drawPath(it, sumPaint) }
        }

        val thresholdY = valueToY(threshold, h, yMax)
        drawClickMarkersAndTimers(
            canvas = canvas,
            drawClicks = drawClicks,
            newest = newest,
            ageRefNs = ageRefNs,
            oldest = oldest,
            windowNs = windowNs,
            w = w,
            h = h,
            thresholdY = thresholdY,
        )
        drawThreshold(canvas, w, h, yMax)
        // Peaks on top: filled dot on apex + value beside it.
        drawVisiblePeaks(canvas, drawSamples, w, h, yMax, oldest, newest, windowNs)
    }

    private fun drawClickMarkersAndTimers(
        canvas: Canvas,
        drawClicks: List<Long>,
        newest: Long,
        ageRefNs: Long,
        oldest: Long,
        windowNs: Long,
        w: Float,
        h: Float,
        thresholdY: Float,
    ) {
        val sorted = drawClicks.sorted()
        val visible = sorted.filter { it in oldest..newest }
        if (visible.isEmpty()) {
            // Still draw timing windows? skip if no markers in view.
            return
        }

        val thrReserve = thresholdTextPaint.measureText(thresholdLabelText()) + dp(14f)
        val maxTextRight = w - thrReserve
        val lineH = markerAgePaint.textSize + dp(3f)
        val ageBaseY = thresholdY - dp(3f)
        val intervalBaseY = thresholdY + markerIntervalPaint.textSize + dp(3f)
        val xPad = dp(4f)

        data class Placed(val left: Float, val right: Float, val baseline: Float)

        fun overlaps(a: Placed, left: Float, right: Float, baseline: Float): Boolean {
            if (left >= a.right || a.left >= right) return false
            return abs(baseline - a.baseline) < lineH * 0.9f
        }

        val agePlaced = ArrayList<Placed>(visible.size)
        for (markerNs in visible) {
            val x = ((markerNs - oldest).toFloat() / windowNs) * w
            canvas.drawLine(x, 0f, x, h, markerPaint)

            val ageLabel = String.format("%.2f", (ageRefNs - markerNs) / 1_000_000_000f)
            val tw = markerAgePaint.measureText(ageLabel)
            val textX = (x + xPad).coerceAtMost((maxTextRight - tw).coerceAtLeast(0f)).coerceAtLeast(0f)
            var textY = ageBaseY
            var guard = 0
            while (guard < 24 && agePlaced.any { overlaps(it, textX, textX + tw, textY) }) {
                textY -= lineH
                guard++
            }
            textY = textY.coerceAtLeast(markerAgePaint.textSize)
            canvas.drawText(ageLabel, textX, textY, markerAgePaint)
            agePlaced.add(Placed(textX, textX + tw, textY))

            if (timingOverlay) {
                drawTimingWindows(canvas, x, oldest, windowNs, w, h, markerNs)
            }
        }

        val intervalPlaced = ArrayList<Placed>(visible.size)
        for (i in 1 until sorted.size) {
            val markerNs = sorted[i]
            if (markerNs !in oldest..newest) continue
            val prevNs = sorted[i - 1]
            val x = ((markerNs - oldest).toFloat() / windowNs) * w
            val intervalLabel = String.format("%.2f", (markerNs - prevNs) / 1_000_000_000f)
            val tw = markerIntervalPaint.measureText(intervalLabel)
            val textX = (x + xPad).coerceAtMost((maxTextRight - tw).coerceAtLeast(0f)).coerceAtLeast(0f)
            var textY = intervalBaseY
            var guard = 0
            while (guard < 24 && intervalPlaced.any { overlaps(it, textX, textX + tw, textY) }) {
                textY += lineH
                guard++
            }
            textY = textY.coerceAtMost(h - dp(2f))
            canvas.drawText(intervalLabel, textX, textY, markerIntervalPaint)
            intervalPlaced.add(Placed(textX, textX + tw, textY))
        }
    }

    private fun drawTimingWindows(
        canvas: Canvas,
        clickX: Float,
        oldest: Long,
        windowNs: Long,
        w: Float,
        h: Float,
        clickNs: Long,
    ) {
        val restNs = (minRestSec * 1_000_000_000f).toLong()
        val motionNs = (minMotionSec * 1_000_000_000f).toLong()

        // Motion looks back left of click; rest extends right of click.
        val motionStartNs = clickNs - motionNs
        val restEndNs = clickNs + restNs

        val motionStartX = ((motionStartNs - oldest).toFloat() / windowNs) * w
        val restEndX = ((restEndNs - oldest).toFloat() / windowNs) * w

        val gateTop = h * 2f / 3f
        val band = h - gateTop
        val restY = gateTop + band * 0.38f
        val motionY = gateTop + band * 0.72f

        restWindowPaint.alpha = if (restGateEnabled) 255 else 110
        motionWindowPaint.alpha = if (motionGateEnabled) 255 else 110

        val motionFrom = motionStartX.coerceIn(0f, w)
        val motionTo = clickX.coerceIn(0f, w)
        if (motionTo > motionFrom) {
            canvas.drawLine(motionFrom, motionY, motionTo, motionY, motionWindowPaint)
            canvas.drawLine(motionFrom, gateTop, motionFrom, h, motionWindowPaint)
            canvas.drawLine(motionTo, gateTop, motionTo, h, motionWindowPaint)
            timingLabelPaint.color = motionWindowPaint.color
            timingLabelPaint.alpha = motionWindowPaint.alpha
            canvas.drawText(
                String.format("M %.1fs", minMotionSec),
                (motionFrom + motionTo) / 2f,
                motionY - dp(3f),
                timingLabelPaint,
            )
        }

        val restFrom = clickX.coerceIn(0f, w)
        val restTo = restEndX.coerceIn(0f, w)
        if (restTo > restFrom) {
            canvas.drawLine(restFrom, restY, restTo, restY, restWindowPaint)
            canvas.drawLine(restFrom, gateTop, restFrom, h, restWindowPaint)
            canvas.drawLine(restTo, gateTop, restTo, h, restWindowPaint)
            timingLabelPaint.color = restWindowPaint.color
            timingLabelPaint.alpha = restWindowPaint.alpha
            canvas.drawText(
                String.format("R %.1fs", minRestSec),
                (restFrom + restTo) / 2f,
                restY - dp(3f),
                timingLabelPaint,
            )
        }
    }

    private fun drawSeries(
        drawSamples: List<Sample>,
        w: Float,
        h: Float,
        yMax: Float,
        oldest: Long,
        windowNs: Long,
        valueOf: (Sample) -> Float,
    ): Path {
        seriesPath.reset()
        var started = false
        forEachSmoothed(drawSamples, valueOf) { ts, value ->
            val x = ((ts - oldest).toFloat() / windowNs) * w
            val y = valueToY(value, h, yMax)
            if (!started) {
                seriesPath.moveTo(x, y)
                started = true
            } else {
                seriesPath.lineTo(x, y)
            }
        }
        return seriesPath
    }

    /** Causal EMA over the sample list — slider retunes the whole visible history. */
    private inline fun forEachSmoothed(
        drawSamples: List<Sample>,
        valueOf: (Sample) -> Float,
        crossinline consumer: (timestampNs: Long, value: Float) -> Unit,
    ) {
        val tau = smoothTauSec
        var ema = Float.NaN
        var prevTs = 0L
        for (sample in drawSamples) {
            val raw = valueOf(sample)
            val value = if (tau <= 0f) {
                raw
            } else {
                val dt = if (prevTs == 0L) 0f else (sample.timestampNs - prevTs) * 1e-9f
                prevTs = sample.timestampNs
                val alpha = if (ema.isNaN() || dt <= 0f) {
                    1f
                } else {
                    (1f - exp((-dt / tau).toDouble()).toFloat()).coerceIn(0f, 1f)
                }
                ema = if (ema.isNaN()) raw else ema + alpha * (raw - ema)
                ema
            }
            consumer(sample.timestampNs, value)
        }
    }

    private data class WavePeak(
        val timestampNs: Long,
        val value: Float,
        val color: Int,
    )

    private fun drawVisiblePeaks(
        canvas: Canvas,
        drawSamples: List<Sample>,
        w: Float,
        h: Float,
        yMax: Float,
        oldest: Long,
        newest: Long,
        windowNs: Long,
    ) {
        val peaks = ArrayList<WavePeak>(24)
        if (showSum && enabledChannels.size > 1) {
            peaks += findPeaksInWindow(
                drawSamples, oldest, newest, windowNs, sumPaint.color,
            ) { sample ->
                enabledChannels.sumOf { (sample.values[it] ?: 0f).toDouble() }.toFloat()
            }
        } else {
            for (channel in enabledChannels) {
                val color = channelPaints[channel]?.color ?: Color.WHITE
                peaks += findPeaksInWindow(
                    drawSamples, oldest, newest, windowNs, color,
                ) { it.values[channel] ?: 0f }
            }
        }
        if (peaks.isEmpty()) return

        // Prefer taller peaks; drop ones that collide in X.
        peaks.sortByDescending { it.value }
        val kept = ArrayList<WavePeak>(MAX_PEAK_LABELS)
        val minDx = w * 0.06f
        for (peak in peaks) {
            if (kept.size >= MAX_PEAK_LABELS) break
            val x = ((peak.timestampNs - oldest).toFloat() / windowNs) * w
            if (kept.any { abs((((it.timestampNs - oldest).toFloat() / windowNs) * w) - x) < minDx }) {
                continue
            }
            kept += peak
        }
        kept.sortBy { it.timestampNs }

        val r = dp(3.5f)
        val gap = dp(5f)
        for (peak in kept) {
            val x = ((peak.timestampNs - oldest).toFloat() / windowNs) * w
            val y = valueToY(peak.value, h, yMax)
            peakDotPaint.color = peak.color
            canvas.drawCircle(x, y, r, peakDotPaint)
            canvas.drawCircle(x, y, r, peakDotRingPaint)

            val label = formatPeakValue(peak.value)
            val textW = peakTextPaint.measureText(label)
            val textH = peakTextPaint.textSize
            // Value beside the dot; flip left near the right edge.
            val textLeft = if (x + gap + textW <= w - dp(4f)) {
                x + gap
            } else {
                x - gap - textW
            }
            val textBaseline = (y + textH * 0.35f).coerceIn(textH + dp(2f), h - dp(2f))
            peakTextPaint.color = peak.color
            canvas.drawText(label, textLeft, textBaseline, peakTextPaint)
        }
    }

    private fun findPeaksInWindow(
        drawSamples: List<Sample>,
        oldest: Long,
        newest: Long,
        windowNs: Long,
        color: Int,
        valueOf: (Sample) -> Float,
    ): List<WavePeak> {
        peakTsBuf.clear()
        peakValBuf.clear()
        // Warm EMA before the window so edge peaks are correct.
        forEachSmoothed(drawSamples, valueOf) { ts, value ->
            if (ts < oldest) return@forEachSmoothed
            if (ts > newest) return@forEachSmoothed
            peakTsBuf.add(ts)
            peakValBuf.add(value)
        }
        val n = peakValBuf.size
        if (n < 3) return emptyList()

        // Peak labels are visual-only — never tied to detect threshold.
        val seriesPeak = yMaxHint(peakValBuf)
        val minHeight = max(0.05f, seriesPeak * 0.06f)
        val minSepNs = max(50_000_000L, windowNs / 40L)
        val radius = 2
        val candidates = ArrayList<WavePeak>(32)
        for (i in radius until n - radius) {
            val v = peakValBuf[i]
            if (v < minHeight) continue
            var isPeak = true
            for (j in (i - radius)..(i + radius)) {
                if (j == i) continue
                if (peakValBuf[j] > v) {
                    isPeak = false
                    break
                }
            }
            if (!isPeak) continue
            // Require a real local rise (not a flat plateau start).
            if (peakValBuf[i - 1] >= v && peakValBuf[i + 1] >= v) continue
            candidates += WavePeak(peakTsBuf[i], v, color)
        }
        if (candidates.isEmpty()) return emptyList()

        candidates.sortByDescending { it.value }
        val out = ArrayList<WavePeak>(candidates.size)
        for (c in candidates) {
            if (out.any { abs(it.timestampNs - c.timestampNs) < minSepNs }) continue
            out += c
        }
        return out
    }

    private fun yMaxHint(values: List<Float>): Float {
        var peak = 0.5f
        for (v in values) peak = max(peak, v)
        return peak
    }

    private fun formatPeakValue(value: Float): String {
        return if (value < 0.1f) {
            String.format("%.2f", value)
        } else {
            String.format("%.1f", value)
        }
    }

    private fun drawHorizontalGrid(canvas: Canvas, w: Float, h: Float) {
        for (i in 1..4) {
            val y = h * i / 5f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
    }

    private fun drawVerticalGrid(
        canvas: Canvas,
        w: Float,
        h: Float,
        oldest: Long,
        newest: Long,
        windowNs: Long,
    ) {
        val oneSecNs = 1_000_000_000L
        // Align to absolute second boundaries so lines scroll with time.
        var t = ((oldest / oneSecNs) + 1) * oneSecNs
        while (t < newest) {
            val x = ((t - oldest).toFloat() / windowNs) * w
            canvas.drawLine(x, 0f, x, h, gridPaint)
            t += oneSecNs
        }
    }

    private fun thresholdLabelText(): String {
        return if (threshold < 0.1f) {
            String.format("%.2f", threshold)
        } else {
            String.format("%.1f", threshold)
        }
    }

    private fun drawThreshold(canvas: Canvas, w: Float, h: Float, yMax: Float) {
        val y = valueToY(threshold, h, yMax)
        canvas.drawLine(0f, y, w, y, thresholdPaint)
        val label = thresholdLabelText()
        val textY = (y - dp(6f)).coerceAtLeast(thresholdTextPaint.textSize + dp(4f))
        canvas.drawText(label, w - dp(8f), textY, thresholdTextPaint)
    }

    private fun currentYMax(drawSamples: List<Sample> = samples.toList()): Float {
        if (!autoscale) return manualYMax
        var peak = max(threshold * 1.5f, 0.5f)
        for (channel in enabledChannels) {
            forEachSmoothed(drawSamples, { it.values[channel] ?: 0f }) { _, v ->
                peak = max(peak, v)
            }
        }
        if (showSum && enabledChannels.size > 1) {
            forEachSmoothed(drawSamples, { sample ->
                enabledChannels.sumOf { (sample.values[it] ?: 0f).toDouble() }.toFloat()
            }) { _, v ->
                peak = max(peak, v)
            }
        }
        return max(peak * 1.15f, 0.5f)
    }

    private fun valueToY(value: Float, h: Float, yMax: Float): Float {
        val normalized = (value / yMax).coerceIn(0f, 1f)
        return h - normalized * h
    }

    private fun stroke(colorHex: String): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(colorHex)
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            strokeJoin = Paint.Join.ROUND
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val MAX_SAMPLES = 8000
        private const val RETAIN_HISTORY_SEC = 120f
        private const val TIME_WINDOW_MIN_SEC = 2f
        private const val TIME_WINDOW_MAX_SEC = 120f
        private const val SCALE_AXIS_UNSET = 0
        private const val SCALE_AXIS_Y = 1
        private const val SCALE_AXIS_TIME = 2
        const val Y_ZOOM_MIN = 0.5f
        const val Y_ZOOM_MAX = 20f
        const val SMOOTH_TAU_MAX_SEC = 0.5f
        const val SMOOTH_TAU_DEFAULT_SEC = 0f
        private const val MAX_PEAK_LABELS = 14
    }
}
