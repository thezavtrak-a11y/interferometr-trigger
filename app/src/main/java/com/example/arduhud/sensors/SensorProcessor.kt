package com.example.arduhud.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.arduhud.stats.ClickPulse
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class SensorProcessor(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _motionData = MutableStateFlow(MotionData())
    val motionData: StateFlow<MotionData> = _motionData.asStateFlow()

    private val _clickEvents = MutableSharedFlow<ClickPulse>(extraBufferCapacity = 8)
    val clickEvents: SharedFlow<ClickPulse> = _clickEvents.asSharedFlow()

    private var linearX = 0f
    private var linearY = 0f
    private var linearZ = 0f
    private var gyroX = 0f
    private var gyroY = 0f
    private var gyroZ = 0f

    private var enabledChannels: Set<SensorChannel> = setOf(
        SensorChannel.ACC_MAG,
        SensorChannel.GYRO_MAG,
    )
    private var useSum: Boolean = true

    private var isMoving = false
    private var threshold = DEFAULT_THRESHOLD
    private var lastClickTimeMs = 0L
    private var running = false
    private var detectPaused = false

    private var minMotionSec = DEFAULT_MIN_MOTION_SEC
    private var minRestSec = DEFAULT_MIN_REST_SEC

    private var motionStartNs = 0L
    private var restStartNs = 0L
    private var pendingRestDetect = false

    /** Dominant signed axis accumulated during the current motion burst. */
    private var motionPeakAbs = 0f
    private var motionPeakSign = 0
    private var motionPeakAxis: SensorChannel? = null

    /** Same EMA τ as oscilloscope — detect threshold compares smoothed activity. */
    private var smoothTauSec = 0f
    private var lastEmaNs = 0L
    private val channelEma = HashMap<SensorChannel, Float>()

    private var demoMode = false
    private var demoStartMs = 0L
    private val demoHandler = Handler(Looper.getMainLooper())
    private val demoRunnable = object : Runnable {
        override fun run() {
            if (!demoMode) return
            applyDemoSample()
            demoHandler.postDelayed(this, DEMO_PERIOD_MS)
        }
    }

    fun setThreshold(newThreshold: Float) {
        threshold = newThreshold.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
    }

    fun getThreshold(): Float = threshold

    fun setEnabledChannels(channels: Set<SensorChannel>) {
        enabledChannels = channels
        updateMotionState()
    }

    fun getEnabledChannels(): Set<SensorChannel> = enabledChannels

    fun setUseSum(enabled: Boolean) {
        useSum = enabled
        updateMotionState()
    }

    fun getUseSum(): Boolean = useSum

    fun setSmoothTauSec(seconds: Float) {
        smoothTauSec = seconds.coerceAtLeast(0f)
        if (smoothTauSec <= 0f) {
            channelEma.clear()
            lastEmaNs = 0L
        }
    }

    fun getSmoothTauSec(): Float = smoothTauSec

    fun isMotionGateEnabled(): Boolean = minMotionSec > 0f

    fun setMinMotionSec(seconds: Float) {
        minMotionSec = seconds.coerceIn(MIN_MOTION_SEC, MAX_MOTION_SEC)
    }

    fun getMinMotionSec(): Float = minMotionSec

    fun isRestGateEnabled(): Boolean = minRestSec > 0f

    fun setMinRestSec(seconds: Float) {
        minRestSec = seconds.coerceIn(MIN_REST_SEC, MAX_REST_SEC)
        if (!isRestGateEnabled()) pendingRestDetect = false
    }

    fun getMinRestSec(): Float = minRestSec

    fun setDetectPaused(paused: Boolean) {
        detectPaused = paused
        if (paused) {
            isMoving = false
            pendingRestDetect = false
            restStartNs = 0L
        }
    }

    fun isDetectPaused(): Boolean = detectPaused

    fun startTutorialDemo() {
        demoMode = true
        isMoving = false
        pendingRestDetect = false
        restStartNs = 0L
        motionStartNs = 0L
        channelEma.clear()
        lastEmaNs = 0L
        lastClickTimeMs = 0L
        demoStartMs = SystemClock.elapsedRealtime()
        demoHandler.removeCallbacks(demoRunnable)
        demoHandler.post(demoRunnable)
    }

    fun stopTutorialDemo() {
        if (!demoMode) return
        demoMode = false
        demoHandler.removeCallbacks(demoRunnable)
        linearX = 0f
        linearY = 0f
        linearZ = 0f
        gyroX = 0f
        gyroY = 0f
        gyroZ = 0f
        isMoving = false
        pendingRestDetect = false
        restStartNs = 0L
        channelEma.clear()
        lastEmaNs = 0L
        updateMotionState()
    }

    private fun applyDemoSample() {
        val t = (SystemClock.elapsedRealtime() - demoStartMs) / 1000f
        val phase = t % DEMO_CYCLE_SEC
        val bursting = phase >= DEMO_BURST_START && phase < DEMO_BURST_END
        if (bursting) {
            val u = (phase - DEMO_BURST_START) / (DEMO_BURST_END - DEMO_BURST_START)
            val envelope = sin(u * Math.PI).toFloat()
            linearX = 0.2f * envelope
            linearY = 4.4f * envelope
            linearZ = 0.3f * envelope
            gyroX = 1.6f * envelope
            gyroY = 0.4f * envelope
            gyroZ = 0.2f * envelope
        } else {
            val n = 0.12f * sin((t * 9.0).toDouble()).toFloat()
            linearX = n * 0.4f
            linearY = n
            linearZ = n * 0.2f
            gyroX = n * 0.15f
            gyroY = 0f
            gyroZ = 0f
        }
        updateMotionState()
    }

    fun start() {
        if (running) return
        running = true
        linearAccel?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (demoMode) return
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                linearX = event.values[0]
                linearY = event.values[1]
                linearZ = event.values[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroX = event.values[0]
                gyroY = event.values[1]
                gyroZ = event.values[2]
            }
            else -> return
        }

        updateMotionState()
    }

    private fun updateMotionState() {
        val nowNs = System.nanoTime()
        val linearMagnitude = magnitude(linearX, linearY, linearZ)
        val gyroMagnitude = magnitude(gyroX, gyroY, gyroZ)

        val rawValues = linkedMapOf(
            SensorChannel.ACC_X to abs(linearX),
            SensorChannel.ACC_Y to abs(linearY),
            SensorChannel.ACC_Z to abs(linearZ),
            SensorChannel.ACC_MAG to linearMagnitude,
            SensorChannel.GYRO_X to abs(gyroX),
            SensorChannel.GYRO_Y to abs(gyroY),
            SensorChannel.GYRO_Z to abs(gyroZ),
            SensorChannel.GYRO_MAG to gyroMagnitude,
        )
        // Raw → waveform (draw-time EMA). Smoothed → detect / activity readout.
        val values = rawValues
        val smoothValues = smoothChannelValues(nowNs, rawValues)
        val selected = enabledChannels.mapNotNull { smoothValues[it] }
        val activity = when {
            selected.isEmpty() -> 0f
            useSum -> selected.sum()
            else -> selected.maxOrNull() ?: 0f
        }
        val releaseThreshold = threshold * HYSTERESIS_RATIO

        var clickMarker: Long? = null

        if (!detectPaused) {
            when {
                !isMoving && activity > threshold -> {
                    isMoving = true
                    motionStartNs = nowNs
                    restStartNs = 0L
                    pendingRestDetect = false
                    resetMotionDirectionPeaks()
                    sampleMotionDirectionPeaks()
                }
                isMoving -> {
                    sampleMotionDirectionPeaks()
                    if (activity < releaseThreshold) {
                        isMoving = false
                        restStartNs = nowNs
                        val motionMs = (nowNs - motionStartNs) / 1_000_000L
                        val motionOk = !isMotionGateEnabled() ||
                            motionMs >= (minMotionSec * 1000f).toLong()
                        if (motionOk) {
                            if (isRestGateEnabled()) {
                                pendingRestDetect = true
                            } else {
                                clickMarker = emitClickIfAllowed(nowNs)
                            }
                        }
                    }
                }
            }

            if (clickMarker == null &&
                pendingRestDetect &&
                !isMoving &&
                isRestGateEnabled() &&
                restStartNs > 0L
            ) {
                val restMs = (nowNs - restStartNs) / 1_000_000L
                if (restMs >= (minRestSec * 1000f).toLong()) {
                    pendingRestDetect = false
                    clickMarker = emitClickIfAllowed(nowNs)
                }
            }
        }

        _motionData.value = MotionData(
            timestampNs = nowNs,
            linearAccelX = linearX,
            linearAccelY = linearY,
            linearAccelZ = linearZ,
            gyroX = gyroX,
            gyroY = gyroY,
            gyroZ = gyroZ,
            linearMagnitude = linearMagnitude,
            gyroMagnitude = gyroMagnitude,
            channelValues = values,
            activity = activity,
            isMoving = if (detectPaused) false else isMoving,
            useSum = useSum,
            enabledChannels = enabledChannels,
            clickMarkerNs = clickMarker,
        )
    }

    private fun emitClickIfAllowed(nowNs: Long): Long? {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastClickTimeMs < CLICK_DEBOUNCE_MS) return null
        lastClickTimeMs = nowMs
        val pulse = ClickPulse(
            timestampNs = nowNs,
            axis = motionPeakAxis,
            sign = motionPeakSign,
            peakAbs = motionPeakAbs,
        )
        _clickEvents.tryEmit(pulse)
        return nowNs
    }

    private fun resetMotionDirectionPeaks() {
        motionPeakAbs = 0f
        motionPeakSign = 0
        motionPeakAxis = null
    }

    private fun sampleMotionDirectionPeaks() {
        for (channel in enabledChannels) {
            when (channel) {
                SensorChannel.ACC_X -> considerPeak(SensorChannel.ACC_X, linearX)
                SensorChannel.ACC_Y -> considerPeak(SensorChannel.ACC_Y, linearY)
                SensorChannel.ACC_Z -> considerPeak(SensorChannel.ACC_Z, linearZ)
                SensorChannel.ACC_MAG -> {
                    considerPeak(SensorChannel.ACC_X, linearX)
                    considerPeak(SensorChannel.ACC_Y, linearY)
                    considerPeak(SensorChannel.ACC_Z, linearZ)
                }
                SensorChannel.GYRO_X -> considerPeak(SensorChannel.GYRO_X, gyroX)
                SensorChannel.GYRO_Y -> considerPeak(SensorChannel.GYRO_Y, gyroY)
                SensorChannel.GYRO_Z -> considerPeak(SensorChannel.GYRO_Z, gyroZ)
                SensorChannel.GYRO_MAG -> {
                    considerPeak(SensorChannel.GYRO_X, gyroX)
                    considerPeak(SensorChannel.GYRO_Y, gyroY)
                    considerPeak(SensorChannel.GYRO_Z, gyroZ)
                }
            }
        }
    }

    private fun considerPeak(axis: SensorChannel, signed: Float) {
        val a = abs(signed)
        if (a > motionPeakAbs) {
            motionPeakAbs = a
            motionPeakSign = when {
                signed > 0f -> 1
                signed < 0f -> -1
                else -> 0
            }
            motionPeakAxis = axis
        }
    }

    private fun smoothChannelValues(
        nowNs: Long,
        raw: Map<SensorChannel, Float>,
    ): Map<SensorChannel, Float> {
        if (smoothTauSec <= 0f) return raw
        val dt = if (lastEmaNs == 0L) 0f else (nowNs - lastEmaNs) * 1e-9f
        lastEmaNs = nowNs
        val alpha = if (dt <= 0f) {
            1f
        } else {
            (1f - exp((-dt / smoothTauSec).toDouble()).toFloat()).coerceIn(0f, 1f)
        }
        val out = LinkedHashMap<SensorChannel, Float>(raw.size)
        for ((ch, v) in raw) {
            val prev = channelEma[ch]
            val next = if (prev == null) v else prev + alpha * (v - prev)
            channelEma[ch] = next
            out[ch] = next
        }
        return out
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun magnitude(x: Float, y: Float, z: Float): Float {
        return sqrt(x * x + y * y + z * z)
    }

    companion object {
        const val DEFAULT_THRESHOLD = 2.0f
        const val MIN_THRESHOLD = 0.01f
        const val MAX_THRESHOLD = 15.0f
        const val HYSTERESIS_RATIO = 0.6f
        const val CLICK_DEBOUNCE_MS = 200L

        const val DEFAULT_MIN_MOTION_SEC = 0.0f
        const val MIN_MOTION_SEC = 0.0f
        const val MAX_MOTION_SEC = 5.0f
        const val GATE_STEP_SEC = 0.1f

        const val DEFAULT_MIN_REST_SEC = 0.0f
        const val MIN_REST_SEC = 0.0f
        const val MAX_REST_SEC = 10.0f

        private const val DEMO_PERIOD_MS = 20L
        private const val DEMO_CYCLE_SEC = 2.6f
        private const val DEMO_BURST_START = 0.7f
        private const val DEMO_BURST_END = 1.25f
    }
}
