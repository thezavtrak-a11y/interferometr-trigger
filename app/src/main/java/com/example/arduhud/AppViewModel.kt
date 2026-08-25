package com.example.arduhud

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.arduhud.link.EspLinkForegroundService
import com.example.arduhud.link.EspLinkState
import com.example.arduhud.sensors.MotionData
import com.example.arduhud.sensors.SensorChannel
import com.example.arduhud.sensors.SensorProcessor
import com.example.arduhud.stats.ClickJournalEntry
import com.example.arduhud.stats.ClickPulse
import com.example.arduhud.stats.ClickSessionSummary
import com.example.arduhud.stats.ClickStatsRecorder
import com.example.arduhud.usb.EspUsbSerialManager
import com.example.arduhud.wifi.EspLinkEvent
import com.example.arduhud.wifi.EspWifiLinkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LinkTransport {
    None,
    Wifi,
    Usb,
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val sensorProcessor = SensorProcessor(application)
    private val wifiLink = EspWifiLinkManager(application)
    private val usbLink = EspUsbSerialManager(application)
    private val clickStats = ClickStatsRecorder()

    val motionData: StateFlow<MotionData> = sensorProcessor.motionData
    val clickEvents: SharedFlow<ClickPulse> = sensorProcessor.clickEvents

    private val _activeTransport = MutableStateFlow(LinkTransport.None)
    val activeTransport: StateFlow<LinkTransport> = _activeTransport.asStateFlow()

    private val _connectionState = MutableStateFlow<EspLinkState>(EspLinkState.Disconnected)
    val connectionState: StateFlow<EspLinkState> = _connectionState.asStateFlow()

    private val _debugLog = MutableStateFlow<List<String>>(emptyList())
    val debugLog: StateFlow<List<String>> = _debugLog.asStateFlow()

    private val _penControlEnabled = MutableStateFlow(true)
    val penControlEnabled: StateFlow<Boolean> = _penControlEnabled.asStateFlow()

    val linkEvents: SharedFlow<EspLinkEvent> = wifiLink.linkEvents

    private val _clickJournal = MutableStateFlow<List<ClickJournalEntry>>(emptyList())
    val clickJournal: StateFlow<List<ClickJournalEntry>> = _clickJournal.asStateFlow()

    private val _clickSessionSummary = MutableStateFlow<ClickSessionSummary?>(null)
    val clickSessionSummary: StateFlow<ClickSessionSummary?> = _clickSessionSummary.asStateFlow()

    private val _clickStatsArmed = MutableStateFlow(false)
    val clickStatsArmed: StateFlow<Boolean> = _clickStatsArmed.asStateFlow()

    val wifiSsid: String get() = EspWifiLinkManager.AP_SSID
    val wifiPassword: String get() = EspWifiLinkManager.AP_PASSWORD
    val wifiEndpoint: String get() = "${EspWifiLinkManager.AP_HOST}:${EspWifiLinkManager.AP_PORT}"
    val usbDevices = usbLink.devices

    private var sensorsActive = false
    private var clickOutputEnabled = false
    /** True while MainFragment is resumed — waveform UI wants sensors even offline. */
    private var uiForeground = false

    private val _keepScreenOn = MutableStateFlow(false)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()
    private var lastBatteryPercent = 100
    private val _keepScreenOnAutoOffMessage = MutableStateFlow<String?>(null)
    val keepScreenOnAutoOffMessage: StateFlow<String?> = _keepScreenOnAutoOffMessage.asStateFlow()

    /**
     * After «Найти»: live MOVE packets + inter-chunk dt are recorded.
     * [trackedPos] = sum of sent (dx,dy) from the corner (mouse space).
     */
    private val livePath = ArrayList<MoveSample>(256)
    private var recordingPath = false
    private var lastChunkRt = 0L

    private val _trackedPos = MutableStateFlow<Pair<Int, Int>?>(null)
    val trackedPos: StateFlow<Pair<Int, Int>?> = _trackedPos.asStateFlow()

    /** MOVE chunks + timing; replayed after corner slam. */
    private var rememberedPath: List<MoveSample>? = null
    private val _rememberedPos = MutableStateFlow<Pair<Int, Int>?>(null)
    val rememberedPos: StateFlow<Pair<Int, Int>?> = _rememberedPos.asStateFlow()

    private val _findingOrigin = MutableStateFlow(false)
    val findingOrigin: StateFlow<Boolean> = _findingOrigin.asStateFlow()

    /** True while remembered MOVE path is being sent after corner slam. */
    private val _pathReplaying = MutableStateFlow(false)
    val pathReplaying: StateFlow<Boolean> = _pathReplaying.asStateFlow()

    private var pathArmed = false
    private var findJob: Job? = null
    private var moveFlushJob: Job? = null
    private var pendingMoveDx = 0
    private var pendingMoveDy = 0

    init {
        EspLinkForegroundService.stopCallback =
            EspLinkForegroundService.StopCallback {
                disconnectActiveLink()
            }
        viewModelScope.launch {
            clickEvents.collect { pulse ->
                if (!clickOutputEnabled) return@collect
                clickStats.onClick(pulse)
                _clickJournal.value = clickStats.journal
                if (connectionState.value !is EspLinkState.Connected) return@collect
                if (_findingOrigin.value) return@collect
                linkSendClick()
            }
        }
        viewModelScope.launch {
            wifiLink.connectionState.collect { state ->
                if (_activeTransport.value == LinkTransport.Wifi) {
                    _connectionState.value = state
                    if (state is EspLinkState.Disconnected || state is EspLinkState.Error) {
                        if (_activeTransport.value == LinkTransport.Wifi) {
                            _activeTransport.value = LinkTransport.None
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            usbLink.connectionState.collect { state ->
                if (_activeTransport.value == LinkTransport.Usb) {
                    _connectionState.value = state
                    if (state is EspLinkState.Disconnected || state is EspLinkState.Error) {
                        if (_activeTransport.value == LinkTransport.Usb) {
                            _activeTransport.value = LinkTransport.None
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            wifiLink.logLines.collect { lines ->
                if (_activeTransport.value != LinkTransport.Usb) {
                    _debugLog.value = lines
                }
            }
        }
        viewModelScope.launch {
            usbLink.logLines.collect { lines ->
                if (_activeTransport.value == LinkTransport.Usb) {
                    _debugLog.value = lines
                }
            }
        }
        viewModelScope.launch {
            wifiLink.penControlEnabled.collect { on ->
                if (_activeTransport.value != LinkTransport.Usb) _penControlEnabled.value = on
            }
        }
        viewModelScope.launch {
            usbLink.penControlEnabled.collect { on ->
                if (_activeTransport.value == LinkTransport.Usb) _penControlEnabled.value = on
            }
        }
        viewModelScope.launch {
            connectionState.collect { syncBackgroundRuntime() }
        }
    }

    fun onUiForeground(foreground: Boolean) {
        uiForeground = foreground
        syncBackgroundRuntime()
    }

    /**
     * While SoftAP/TCP is up: FGS + sensors stay on (clicks work minimized).
     * Offline: sensors only for the visible oscilloscope UI.
     */
    private fun syncBackgroundRuntime() {
        val app = getApplication<Application>()
        val connected = connectionState.value is EspLinkState.Connected
        if (connected) {
            EspLinkForegroundService.start(app, clickOutputEnabled)
            startSensors()
        } else {
            EspLinkForegroundService.stop(app)
            if (uiForeground) startSensors() else stopSensors()
        }
    }

    fun setClickOutputEnabled(enabled: Boolean) {
        if (clickOutputEnabled == enabled) {
            syncBackgroundRuntime()
            return
        }
        clickOutputEnabled = enabled
        onClickArmChanged(enabled)
        syncBackgroundRuntime()
    }

    fun isClickOutputEnabled(): Boolean = clickOutputEnabled

    fun toggleClickOutputEnabled(): Boolean {
        clickOutputEnabled = !clickOutputEnabled
        onClickArmChanged(clickOutputEnabled)
        syncBackgroundRuntime()
        return clickOutputEnabled
    }

    private fun onClickArmChanged(armed: Boolean) {
        if (armed) {
            pathArmed = false
            clickStats.arm()
            _clickJournal.value = emptyList()
            _clickSessionSummary.value = null
            _clickStatsArmed.value = true
            maybeReplayRememberedPathOnArm()
        } else {
            pathArmed = false
            _clickSessionSummary.value = clickStats.disarm()
            _clickJournal.value = clickStats.journal
            _clickStatsArmed.value = false
        }
    }

    fun clearClickStats() {
        if (clickOutputEnabled) {
            clickStats.arm()
            _clickJournal.value = emptyList()
            _clickSessionSummary.value = null
            _clickStatsArmed.value = true
        } else {
            clickStats.clear()
            _clickJournal.value = emptyList()
            _clickSessionSummary.value = null
            _clickStatsArmed.value = false
        }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        _keepScreenOn.value = enabled
    }

    fun isKeepScreenOn(): Boolean = _keepScreenOn.value

    /** Edge-trigger: auto-off only when crossing above 10% → ≤10% while keep-on is armed. */
    fun onBatteryPercent(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        if (_keepScreenOn.value &&
            lastBatteryPercent > KEEP_SCREEN_OFF_BATTERY_PCT &&
            clamped <= KEEP_SCREEN_OFF_BATTERY_PCT
        ) {
            _keepScreenOn.value = false
            _keepScreenOnAutoOffMessage.value = "auto"
        }
        lastBatteryPercent = clamped
    }

    fun consumeKeepScreenOnAutoOffMessage() {
        _keepScreenOnAutoOffMessage.value = null
    }

    fun isFindingOrigin(): Boolean = _findingOrigin.value

    /**
     * Slam to top-right, clear path, start recording subsequent MOVE packets.
     * @return false if not connected or already running
     */
    fun findTopRightOrigin(): Boolean {
        if (connectionState.value !is EspLinkState.Connected) return false
        if (_findingOrigin.value) return false
        startMoveJob {
            slamToTopRight()
            livePath.clear()
            lastChunkRt = 0L
            recordingPath = true
            _trackedPos.value = 0 to 0
        }
        return true
    }

    /** Snapshot recorded MOVE chunks (needs prior «Найти»). */
    fun rememberCurrentPosition(): Boolean {
        if (!recordingPath && livePath.isEmpty()) return false
        val snapshot = livePath.toList()
        rememberedPath = snapshot
        _rememberedPos.value = pathSum(snapshot)
        pathArmed = false
        if (clickOutputEnabled) maybeReplayRememberedPathOnArm()
        return true
    }

    fun clearRememberedPosition() {
        rememberedPath = null
        _rememberedPos.value = null
        pathArmed = false
    }

    /**
     * Lightning arm (default off): slam to corner, then replay MOVE+dt
     * (same velocity profile → less Windows accel skew).
     */
    private fun maybeReplayRememberedPathOnArm() {
        if (pathArmed) return
        if (connectionState.value !is EspLinkState.Connected) return
        val path = rememberedPath
        pathArmed = true
        if (path == null) return
        startMoveJob {
            // Blink from arm start (incl. CORNER); pacing must stay off Main.
            _pathReplaying.value = true
            try {
                slamToTopRight()
                for (sample in path) {
                    if (connectionState.value !is EspLinkState.Connected) return@startMoveJob
                    linkSendSuspend("MOVE ${sample.dx} ${sample.dy}")
                    val wait = if (sample.dtMs > 0L) sample.dtMs else HID_PACE_MS
                    delay(wait)
                }
                livePath.clear()
                livePath.addAll(path)
                lastChunkRt = 0L
                recordingPath = true
                _trackedPos.value = pathSum(path)
            } finally {
                _pathReplaying.value = false
            }
        }
    }

    private fun startMoveJob(block: suspend () -> Unit) {
        findJob?.cancel()
        // IO: delay/MOVE must not wait on Main (toolbar animators, waveform).
        findJob = viewModelScope.launch(Dispatchers.IO) {
            _findingOrigin.value = true
            try {
                block()
            } finally {
                _findingOrigin.value = false
                _pathReplaying.value = false
            }
        }
    }

    private suspend fun slamToTopRight() {
        flushPendingMoveImmediate()
        coroutineScope {
            val done = async { linkAwaitLineStartingWith("ACK CORNER DONE", CORNER_TIMEOUT_MS) }
            linkSendSuspend("CORNER")
            if (!done.await()) {
                delay(CORNER_WAIT_MS)
            }
        }
        // Let Windows finish corner clamp / HID settle before path starts.
        delay(CORNER_SETTLE_MS)
    }

    private fun scheduleMoveFlush() {
        if (moveFlushJob?.isActive == true) return
        moveFlushJob = viewModelScope.launch(Dispatchers.IO) {
            flushPendingMoves()
        }
    }

    private suspend fun flushPendingMoves() {
        while (pendingMoveDx != 0 || pendingMoveDy != 0) {
            if (_findingOrigin.value) break
            if (connectionState.value !is EspLinkState.Connected) break
            val stepX = pendingMoveDx.coerceIn(-LIVE_STEP, LIVE_STEP)
            val stepY = pendingMoveDy.coerceIn(-LIVE_STEP, LIVE_STEP)
            pendingMoveDx -= stepX
            pendingMoveDy -= stepY
            linkSendSuspend("MOVE $stepX $stepY")
            if (recordingPath) {
                val now = SystemClock.elapsedRealtime()
                val dt = if (lastChunkRt == 0L) {
                    0L
                } else {
                    (now - lastChunkRt).coerceIn(DT_MIN_MS, DT_MAX_MS)
                }
                lastChunkRt = now
                livePath.add(MoveSample(stepX, stepY, dt))
                refreshTrackedFromLive()
            }
            if (pendingMoveDx != 0 || pendingMoveDy != 0) {
                delay(HID_PACE_MS)
            }
        }
    }

    private fun flushPendingMoveImmediate() {
        moveFlushJob?.cancel()
        moveFlushJob = null
        pendingMoveDx = 0
        pendingMoveDy = 0
    }

    private fun pathSum(path: List<MoveSample>): Pair<Int, Int> {
        var sx = 0
        var sy = 0
        for (s in path) {
            sx += s.dx
            sy += s.dy
        }
        return sx to sy
    }

    private fun refreshTrackedFromLive() {
        _trackedPos.value = if (recordingPath || livePath.isNotEmpty()) pathSum(livePath) else null
    }

    fun startSensors() {
        if (!sensorsActive) {
            sensorsActive = true
            sensorProcessor.start()
        }
    }

    fun stopSensors() {
        if (sensorsActive) {
            sensorsActive = false
            sensorProcessor.stop()
        }
    }

    fun setThreshold(value: Float) {
        sensorProcessor.setThreshold(value)
    }

    fun getThreshold(): Float = sensorProcessor.getThreshold()

    fun setEnabledChannels(channels: Set<SensorChannel>) {
        sensorProcessor.setEnabledChannels(channels)
    }

    fun getEnabledChannels(): Set<SensorChannel> = sensorProcessor.getEnabledChannels()

    fun setUseSum(enabled: Boolean) {
        sensorProcessor.setUseSum(enabled)
    }

    fun getUseSum(): Boolean = sensorProcessor.getUseSum()

    /** Shared EMA τ: oscilloscope draw + click detect threshold. */
    fun setWaveSmoothSec(seconds: Float) {
        val sec = seconds.coerceIn(0f, WAVE_SMOOTH_MAX_SEC)
        sensorProcessor.setSmoothTauSec(sec)
    }

    fun getWaveSmoothSec(): Float = sensorProcessor.getSmoothTauSec()

    fun isMotionGateEnabled(): Boolean = sensorProcessor.isMotionGateEnabled()
    fun setMinMotionSec(seconds: Float) = sensorProcessor.setMinMotionSec(seconds)
    fun getMinMotionSec(): Float = sensorProcessor.getMinMotionSec()

    fun isRestGateEnabled(): Boolean = sensorProcessor.isRestGateEnabled()
    fun setMinRestSec(seconds: Float) = sensorProcessor.setMinRestSec(seconds)
    fun getMinRestSec(): Float = sensorProcessor.getMinRestSec()

    fun setDetectPaused(paused: Boolean) = sensorProcessor.setDetectPaused(paused)
    fun isDetectPaused(): Boolean = sensorProcessor.isDetectPaused()

    fun connectWifi() {
        _activeTransport.value = LinkTransport.Wifi
        usbLink.disconnect()
        wifiLink.connect()
    }

    fun connectUsbOtg() {
        _activeTransport.value = LinkTransport.Usb
        wifiLink.disconnect()
        usbLink.connectFirstDevice()
    }

    fun disconnectWifi() = disconnectActiveLink()

    fun disconnectUsb() = disconnectActiveLink()

    fun disconnectActiveLink() {
        when (_activeTransport.value) {
            LinkTransport.Usb -> usbLink.disconnect()
            LinkTransport.Wifi -> wifiLink.disconnect()
            LinkTransport.None -> {
                usbLink.disconnect()
                wifiLink.disconnect()
            }
        }
        _activeTransport.value = LinkTransport.None
        _connectionState.value = EspLinkState.Disconnected
    }

    fun refreshUsbDevices() = usbLink.refreshDevices()

    fun sendPing(): Boolean = linkSendText("PING")

    fun sendClick(): Boolean = linkSendClick()

    fun sendCustomCommand(text: String): Boolean = linkSendText(text)

    fun sendMouseMove(dx: Int, dy: Int) {
        if (_findingOrigin.value) return
        if (dx == 0 && dy == 0) return
        pendingMoveDx += dx
        pendingMoveDy += dy
        scheduleMoveFlush()
    }

    fun sendAbs(x: Int, y: Int): Boolean = linkSendText("ABS $x $y")

    fun requestEspPos(): Boolean = linkSendText("GET_POS")

    fun sendScroll(delta: Int): Boolean = linkSendText("SCROLL $delta")

    fun sendRightClick(): Boolean = linkSendText("RIGHT_CLICK")

    fun sendButton4Click(): Boolean = linkSendText("BUTTON4")

    fun sendButton5Click(): Boolean = linkSendText("BUTTON5")

    fun clearLinkLog() {
        when (_activeTransport.value) {
            LinkTransport.Usb -> usbLink.clearLog()
            else -> wifiLink.clearLog()
        }
        _debugLog.value = emptyList()
    }

    fun isConnected(): Boolean = connectionState.value is EspLinkState.Connected

    private fun linkSendClick(): Boolean = linkSendText("CLICK")

    private fun linkSendText(text: String): Boolean {
        return when (_activeTransport.value) {
            LinkTransport.Usb -> usbLink.sendText(text)
            LinkTransport.Wifi -> wifiLink.sendText(text)
            LinkTransport.None -> false
        }
    }

    private suspend fun linkSendSuspend(text: String): Boolean {
        return when (_activeTransport.value) {
            LinkTransport.Usb -> usbLink.sendTextSuspend(text)
            LinkTransport.Wifi -> wifiLink.sendTextSuspend(text)
            LinkTransport.None -> false
        }
    }

    private suspend fun linkAwaitLineStartingWith(prefix: String, timeoutMs: Long): Boolean {
        return when (_activeTransport.value) {
            LinkTransport.Usb -> usbLink.awaitLineStartingWith(prefix, timeoutMs)
            LinkTransport.Wifi -> wifiLink.awaitLineStartingWith(prefix, timeoutMs)
            LinkTransport.None -> false
        }
    }

    override fun onCleared() {
        findJob?.cancel()
        moveFlushJob?.cancel()
        EspLinkForegroundService.stopCallback = null
        EspLinkForegroundService.stop(getApplication())
        stopSensors()
        wifiLink.shutdown()
        usbLink.shutdown()
        super.onCleared()
    }

    private data class MoveSample(val dx: Int, val dy: Int, val dtMs: Long)

    companion object {
        private const val FIND_BURSTS = 100
        /**
         * Live HID step (precision off = linear). Was 12 → sluggish vs CORNER@127.
         * Replay uses recorded chunks, so higher live speed stays accurate.
         */
        private const val LIVE_STEP = 80
        private const val HID_PACE_MS = 8L
        private const val DT_MIN_MS = 4L
        private const val DT_MAX_MS = 80L
        private const val CORNER_WAIT_MS = FIND_BURSTS * 10L + 400L
        private const val CORNER_TIMEOUT_MS = 4_000L
        private const val CORNER_SETTLE_MS = 200L
        const val WAVE_SMOOTH_MAX_SEC = 0.5f
        const val KEEP_SCREEN_OFF_BATTERY_PCT = 10
    }
}
