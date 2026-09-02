package com.example.arduhud

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.arduhud.ble.BleConnectionState
import com.example.arduhud.ble.BleHidManager
import com.example.arduhud.ble.HidBondedHost
import com.example.arduhud.ble.HidDebugLog
import com.example.arduhud.ble.MouseButton
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
    /** Phone acts as Bluetooth HID mouse directly to the PC (no ESP). */
    DirectBle,
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val sensorProcessor = SensorProcessor(application)
    private val wifiLink = EspWifiLinkManager(application)
    private val usbLink = EspUsbSerialManager(application)
    private val bleHid = BleHidManager.get(application)
    private val clickStats = ClickStatsRecorder()
    private val appPrefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val motionData: StateFlow<MotionData> = sensorProcessor.motionData
    val clickEvents: SharedFlow<ClickPulse> = sensorProcessor.clickEvents

    private val _activeTransport = MutableStateFlow(LinkTransport.None)
    val activeTransport: StateFlow<LinkTransport> = _activeTransport.asStateFlow()
    private var preferredTransport: LinkTransport = loadPreferredTransport()

    private val _connectionState = MutableStateFlow<EspLinkState>(EspLinkState.Disconnected)
    val connectionState: StateFlow<EspLinkState> = _connectionState.asStateFlow()

    private val _bleUiState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val bleUiState: StateFlow<BleConnectionState> = _bleUiState.asStateFlow()

    private val _bleHosts = MutableStateFlow<List<HidBondedHost>>(emptyList())
    val bleHosts: StateFlow<List<HidBondedHost>> = _bleHosts.asStateFlow()

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
    private var tutorialDemoActive = false
    private var tutorialFlashEnabled = false

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
                if (bleHid.isHidSessionActive) {
                    HidDebugLog.w("FGS stop ignored — HID session stays")
                    true
                } else {
                    disconnectActiveLink()
                    false
                }
            }
        viewModelScope.launch {
            clickEvents.collect { pulse ->
                if (tutorialDemoActive) return@collect
                if (!clickOutputEnabled) return@collect
                clickStats.onClick(pulse)
                _clickJournal.value = clickStats.journal
                if (!isConnected()) return@collect
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
            bleHid.connectionState.collect { state ->
                _bleUiState.value = state
                if (_activeTransport.value != LinkTransport.DirectBle) return@collect
                _connectionState.value = mapBleToEspLink(state)
                syncBackgroundRuntime()
                if (state !is BleConnectionState.Disconnected &&
                    state !is BleConnectionState.Error
                ) {
                    if (!_keepScreenOn.value) setKeepScreenOn(true)
                }
            }
        }
        viewModelScope.launch {
            bleHid.bondedDevices.collect { devices ->
                _bleHosts.value = devices
            }
        }
        viewModelScope.launch {
            wifiLink.logLines.collect { lines ->
                if (_activeTransport.value == LinkTransport.Wifi ||
                    _activeTransport.value == LinkTransport.None
                ) {
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
            HidDebugLog.lines.collect { lines ->
                if (_activeTransport.value == LinkTransport.DirectBle) {
                    _debugLog.value = lines
                }
            }
        }
        viewModelScope.launch {
            wifiLink.penControlEnabled.collect { on ->
                if (_activeTransport.value == LinkTransport.Wifi) _penControlEnabled.value = on
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

    private fun mapBleToEspLink(state: BleConnectionState): EspLinkState {
        return when (state) {
            is BleConnectionState.Connected -> EspLinkState.Connected(state.hostName)
            is BleConnectionState.Connecting -> EspLinkState.Connecting
            BleConnectionState.Registering -> EspLinkState.Connecting
            BleConnectionState.Registered -> EspLinkState.Connecting
            is BleConnectionState.Error -> EspLinkState.Error(state.message)
            BleConnectionState.Disconnected -> EspLinkState.Disconnected
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
        val hidWaitingForHost = _activeTransport.value == LinkTransport.DirectBle &&
            connectionState.value is EspLinkState.Connecting
        val hidActive = _activeTransport.value == LinkTransport.DirectBle &&
            bleHid.isHidSessionActive
        if (connected || hidWaitingForHost || hidActive) {
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
        if (!isConnected()) return false
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
        if (!isConnected()) return
        val path = rememberedPath
        pathArmed = true
        if (path == null) return
        startMoveJob {
            // Blink from arm start (incl. CORNER); pacing must stay off Main.
            _pathReplaying.value = true
            try {
                slamToTopRight()
                for (sample in path) {
                    if (!isConnected()) return@startMoveJob
                    if (_activeTransport.value == LinkTransport.DirectBle) {
                        bleHid.sendMouseMove(sample.dx, sample.dy)
                    } else {
                        linkSendSuspend("MOVE ${sample.dx} ${sample.dy}")
                    }
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
        if (_activeTransport.value == LinkTransport.DirectBle) {
            // No ESP absolute corner — approximate with relative HID bursts.
            repeat(FIND_BURSTS) {
                bleHid.sendMouseMove(127, -127)
                delay(10L)
            }
            delay(CORNER_SETTLE_MS)
            return
        }
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
            if (!isConnected()) break
            val stepX = pendingMoveDx.coerceIn(-LIVE_STEP, LIVE_STEP)
            val stepY = pendingMoveDy.coerceIn(-LIVE_STEP, LIVE_STEP)
            pendingMoveDx -= stepX
            pendingMoveDy -= stepY
            if (_activeTransport.value == LinkTransport.DirectBle) {
                bleHid.sendMouseMove(stepX, stepY)
            } else {
                linkSendSuspend("MOVE $stepX $stepY")
            }
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

    fun setTutorialDemo(active: Boolean) {
        if (!active) tutorialFlashEnabled = false
        if (tutorialDemoActive == active) return
        tutorialDemoActive = active
        if (active) {
            startSensors()
            sensorProcessor.startTutorialDemo()
        } else {
            sensorProcessor.stopTutorialDemo()
        }
    }

    fun isTutorialDemo(): Boolean = tutorialDemoActive

    fun setTutorialFlashEnabled(enabled: Boolean) {
        tutorialFlashEnabled = enabled
    }

    fun isTutorialFlashEnabled(): Boolean = tutorialFlashEnabled

    fun setPreferredTransport(type: LinkTransport) {
        if (type == LinkTransport.None) return
        preferredTransport = type
        appPrefs.edit().putString(PREF_TRANSPORT, type.name).apply()
    }

    private fun loadPreferredTransport(): LinkTransport {
        val name = appPrefs.getString(PREF_TRANSPORT, null) ?: return LinkTransport.None
        return runCatching { LinkTransport.valueOf(name) }.getOrNull()
            ?.takeIf { it != LinkTransport.None }
            ?: LinkTransport.None
    }

    fun resolvedPreferredTransport(): LinkTransport {
        if (preferredTransport != LinkTransport.None) return preferredTransport
        if (_activeTransport.value != LinkTransport.None) return _activeTransport.value
        return LinkTransport.DirectBle
    }

    fun isAnyLinkActive(): Boolean {
        val ble = _bleUiState.value
        val hidBusy = ble is BleConnectionState.Connected ||
            ble is BleConnectionState.Connecting ||
            ble is BleConnectionState.Registering ||
            ble is BleConnectionState.Registered
        val link = _connectionState.value
        val espBusy = link is EspLinkState.Connected ||
            link is EspLinkState.Connecting ||
            link is EspLinkState.RequestingPermission
        return hidBusy || espBusy || _activeTransport.value != LinkTransport.None
    }

    fun togglePreferredLink(hostActivity: Activity) {
        if (isAnyLinkActive()) {
            disconnectActiveLink()
            return
        }
        when (resolvedPreferredTransport()) {
            LinkTransport.Wifi -> connectWifi()
            LinkTransport.Usb -> connectUsbOtg()
            LinkTransport.DirectBle, LinkTransport.None -> registerDirectBle(hostActivity)
        }
    }

    fun connectWifi() {
        bleHid.disconnectHost()
        bleHid.unregister()
        setPreferredTransport(LinkTransport.Wifi)
        _activeTransport.value = LinkTransport.Wifi
        usbLink.disconnect()
        wifiLink.connect()
    }

    fun connectUsbOtg() {
        bleHid.disconnectHost()
        bleHid.unregister()
        setPreferredTransport(LinkTransport.Usb)
        _activeTransport.value = LinkTransport.Usb
        wifiLink.disconnect()
        usbLink.connectFirstDevice()
    }

    fun onBluetoothReady() {
        bleHid.connectHidProfile()
        bleHid.refreshBondedDevices()
    }

    /** Register phone as Bluetooth HID mouse (Air Mouse path). Pair PC first in system BT. */
    fun registerDirectBle(hostActivity: Activity? = null): Boolean {
        _activeTransport.value = LinkTransport.DirectBle
        setPreferredTransport(LinkTransport.DirectBle)
        _penControlEnabled.value = true
        wifiLink.disconnect()
        usbLink.disconnect()
        bleHid.refreshBondedDevices()
        return bleHid.register(hostActivity)
    }

    fun connectBleHost(address: String): Boolean {
        _activeTransport.value = LinkTransport.DirectBle
        setPreferredTransport(LinkTransport.DirectBle)
        _penControlEnabled.value = true
        wifiLink.disconnect()
        usbLink.disconnect()
        return bleHid.connectToHostAddress(address)
    }

    /** Prefer COMPUTER / *-PC bonded host; skip AirMouse-like peripherals. */
    fun connectPreferredBleHost(): Boolean {
        bleHid.refreshBondedDevices()
        val preferred = _bleHosts.value.firstOrNull { it.likelyHost }
            ?: _bleHosts.value.firstOrNull {
                !it.name.contains("airmouse", ignoreCase = true) &&
                    !it.name.contains("mouse", ignoreCase = true)
            }
        val address = preferred?.address ?: bleHid.lastHostOrDefault()
        HidDebugLog.i("connectPreferredBleHost → ${preferred?.name ?: address}")
        return connectBleHost(address)
    }

    fun connectLastHidHost(): Boolean = connectBleHost(bleHid.lastHostOrDefault())

    fun forgetBleHost(address: String): Boolean {
        if (bleHid.isHostConnected) {
            HidDebugLog.w("forget blocked while HID connected")
            return false
        }
        return bleHid.forgetAddress(address)
    }

    fun refreshBleHosts() = bleHid.refreshBondedDevices()

    fun disconnectWifi() = disconnectActiveLink()

    fun disconnectUsb() = disconnectActiveLink()

    fun disconnectDirectBle() = disconnectActiveLink()

    fun disconnectActiveLink() {
        when (_activeTransport.value) {
            LinkTransport.Usb -> usbLink.disconnect()
            LinkTransport.Wifi -> wifiLink.disconnect()
            LinkTransport.DirectBle -> {
                bleHid.disconnectHost()
                bleHid.unregister()
            }
            LinkTransport.None -> {
                usbLink.disconnect()
                wifiLink.disconnect()
                bleHid.disconnectHost()
                bleHid.unregister()
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

    fun sendScroll(delta: Int): Boolean {
        if (_activeTransport.value == LinkTransport.DirectBle) {
            if (!isHidMouseReady()) return false
            bleHid.sendScroll(delta)
            return true
        }
        return linkSendText("SCROLL $delta")
    }

    fun sendRightClick(): Boolean {
        if (_activeTransport.value == LinkTransport.DirectBle) {
            if (!isHidMouseReady()) return false
            bleHid.sendRightClick()
            return true
        }
        return linkSendText("RIGHT_CLICK")
    }

    fun sendButton4Click(): Boolean {
        if (_activeTransport.value == LinkTransport.DirectBle) {
            if (!isHidMouseReady()) return false
            bleHid.sendExtraButtonClick(MouseButton.BUTTON4)
            return true
        }
        return linkSendText("BUTTON4")
    }

    fun sendButton5Click(): Boolean {
        if (_activeTransport.value == LinkTransport.DirectBle) {
            if (!isHidMouseReady()) return false
            bleHid.sendExtraButtonClick(MouseButton.BUTTON5)
            return true
        }
        return linkSendText("BUTTON5")
    }

    fun clearLinkLog() {
        when (_activeTransport.value) {
            LinkTransport.Usb -> usbLink.clearLog()
            LinkTransport.DirectBle -> HidDebugLog.clear()
            else -> wifiLink.clearLog()
        }
        _debugLog.value = emptyList()
    }

    fun isConnected(): Boolean =
        connectionState.value is EspLinkState.Connected || isHidMouseReady()

    private fun isHidMouseReady(): Boolean =
        _activeTransport.value == LinkTransport.DirectBle && bleHid.isHostConnected

    private fun linkSendClick(): Boolean {
        if (_activeTransport.value == LinkTransport.DirectBle) {
            if (!isHidMouseReady()) return false
            bleHid.sendClick()
            return true
        }
        return linkSendText("CLICK")
    }

    private fun linkSendText(text: String): Boolean {
        return when (_activeTransport.value) {
            LinkTransport.Usb -> usbLink.sendText(text)
            LinkTransport.Wifi -> wifiLink.sendText(text)
            LinkTransport.DirectBle -> dispatchBleCommand(text)
            LinkTransport.None -> false
        }
    }

    private suspend fun linkSendSuspend(text: String): Boolean {
        return when (_activeTransport.value) {
            LinkTransport.Usb -> usbLink.sendTextSuspend(text)
            LinkTransport.Wifi -> wifiLink.sendTextSuspend(text)
            LinkTransport.DirectBle -> dispatchBleCommand(text)
            LinkTransport.None -> false
        }
    }

    private fun dispatchBleCommand(text: String): Boolean {
        if (!isHidMouseReady()) return false
        val parts = text.trim().split(Regex("\\s+"))
        return when (parts.firstOrNull()?.uppercase()) {
            "CLICK" -> {
                bleHid.sendClick()
                true
            }
            "RIGHT_CLICK" -> {
                bleHid.sendRightClick()
                true
            }
            "BUTTON4" -> {
                bleHid.sendExtraButtonClick(MouseButton.BUTTON4)
                true
            }
            "BUTTON5" -> {
                bleHid.sendExtraButtonClick(MouseButton.BUTTON5)
                true
            }
            "SCROLL" -> {
                val d = parts.getOrNull(1)?.toIntOrNull() ?: return false
                bleHid.sendScroll(d)
                true
            }
            "MOVE" -> {
                val dx = parts.getOrNull(1)?.toIntOrNull() ?: return false
                val dy = parts.getOrNull(2)?.toIntOrNull() ?: return false
                bleHid.sendMouseMove(dx, dy)
                true
            }
            "PING" -> {
                HidDebugLog.i("PONG (local BT HID)")
                true
            }
            "CORNER", "ABS", "GET_POS", "HELLO", "BLESTAT", "STATUS" -> {
                HidDebugLog.w("Команда $text не для DirectBle (только ESP)")
                false
            }
            else -> {
                HidDebugLog.w("Неизвестная команда для BT HID: $text")
                false
            }
        }
    }

    private suspend fun linkAwaitLineStartingWith(prefix: String, timeoutMs: Long): Boolean {
        return when (_activeTransport.value) {
            LinkTransport.Usb -> usbLink.awaitLineStartingWith(prefix, timeoutMs)
            LinkTransport.Wifi -> wifiLink.awaitLineStartingWith(prefix, timeoutMs)
            LinkTransport.DirectBle -> true // relative slam has no ACK
            LinkTransport.None -> false
        }
    }

    override fun onCleared() {
        findJob?.cancel()
        moveFlushJob?.cancel()
        if (bleHid.isHidSessionActive) {
            HidDebugLog.w("onCleared: HID stays registered (FGS owns session)")
        } else {
            EspLinkForegroundService.stopCallback = null
            EspLinkForegroundService.stop(getApplication())
            bleHid.shutdown()
        }
        stopSensors()
        sensorProcessor.stopTutorialDemo()
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
        private const val PREFS = "arduhud_prefs"
        private const val PREF_TRANSPORT = "preferred_transport"
    }
}
