package com.example.arduhud.ble

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class BleHidManager(context: Context) {

    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hidExecutor = Executors.newSingleThreadExecutor()

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedHost: BluetoothDevice? = null
    private var registered = false
    private var profileReady = false
    private var registrationInFlight = false
    private var connectingInFlight = false
    private var pendingConnectDevice: BluetoothDevice? = null
    private var originalAdapterName: String? = null
    private var connectRetryCount = 0
    private var hadHostSession = false
    private var keepAliveJob: Job? = null
    private var reopenJob: Job? = null
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val isHidSessionActive: Boolean
        get() = registered || connectedHost != null ||
            _connectionState.value is BleConnectionState.Connecting ||
            _connectionState.value is BleConnectionState.Registering ||
            _connectionState.value is BleConnectionState.Registered

    /** True when Windows is actually taking HID input reports. */
    val isHostConnected: Boolean
        get() = connectedHost != null && _connectionState.value is BleConnectionState.Connected

    private var proofNudgePending = false

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _bondedDevices = MutableStateFlow<List<HidBondedHost>>(emptyList())
    val bondedDevices: StateFlow<List<HidBondedHost>> = _bondedDevices.asStateFlow()

    private var bondReceiverRegistered = false
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = extraBluetoothDevice(intent)
                    val bond = intent?.getIntExtra(
                        BluetoothDevice.EXTRA_BOND_STATE,
                        BluetoothDevice.BOND_NONE,
                    )
                    HidDebugLog.i(
                        "bond ${device?.address} → ${bondName(bond ?: -1)}",
                    )
                    if (bond == BluetoothDevice.BOND_BONDED && device != null) {
                        persistLastHost(device.address)
                        if (registered && connectedHost == null) {
                            val pending = pendingConnectDevice
                            if (pending == null || pending.address == device.address) {
                                HidDebugLog.i("bond complete — hid.connect(${device.address})")
                                connectingInFlight = false
                                performConnect(device)
                            }
                        }
                    }
                    refreshBondedDevices()
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> refreshBondedDevices()
            }
        }
    }

    val debugLog = HidDebugLog.lines

    val isSupported: Boolean
        get() = bluetoothAdapter != null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                profileReady = true
                HidDebugLog.i("HID profile connected")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                profileReady = false
                registered = false
                registrationInFlight = false
                connectingInFlight = false
                pendingConnectDevice = null
                connectedHost = null
                _connectionState.value = BleConnectionState.Disconnected
                HidDebugLog.w("HID profile disconnected")
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@BleHidManager.registered = registered
            registrationInFlight = false
            HidDebugLog.i("onAppStatusChanged registered=$registered device=${pluggedDevice?.name}")
            if (registered) {
                if (pluggedDevice != null && connectedHost == null && !connectingInFlight) {
                    HidDebugLog.i("plugged host ${pluggedDevice.name ?: pluggedDevice.address} — auto-connect")
                    persistLastHost(pluggedDevice.address)
                    performConnect(pluggedDevice)
                } else if (connectedHost == null && !connectingInFlight) {
                    _connectionState.value = BleConnectionState.Registered
                    connectAnyKnownHost()
                    if (connectedHost == null && !connectingInFlight) {
                        // Wait for the discoverable Allow dialog so Windows can see Classic SDP.
                        scope.launch {
                            delay(CONNECT_AFTER_REGISTER_MS)
                            if (registered && connectedHost == null && !connectingInFlight) {
                                connectPersistedHost()
                            }
                        }
                    }
                }
                flushPendingConnect()
            } else {
                // Pairing UI / brief stack glitch can fire registered=false. Keep SDP
                // intent: do not wipe last host; FGS + retry will re-register.
                HidDebugLog.w("onAppStatusChanged registered=false — keep last host, do not drop name yet")
                connectedHost = null
                connectingInFlight = false
                pendingConnectDevice = null
                _connectionState.value = BleConnectionState.Disconnected
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            val stateName = connectionStateName(state)
            HidDebugLog.i("onConnectionStateChanged ${device?.name} state=$stateName")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectingInFlight = false
                    connectRetryCount = 0
                    hadHostSession = true
                    connectedHost = device
                    device?.address?.let { persistLastHost(it) }
                    val name = device?.name ?: device?.address ?: "Unknown"
                    _connectionState.value = BleConnectionState.Connected(name)
                    device?.let { forbidAudioProfiles(it) }
                    onHidFullyConnected(nudge = proofNudgePending)
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    connectingInFlight = true
                    _connectionState.value = BleConnectionState.Connecting(
                        device?.name ?: device?.address ?: "?",
                    )
                }
                BluetoothProfile.STATE_DISCONNECTING -> {
                    connectingInFlight = false
                    _connectionState.value = if (registered) {
                        BleConnectionState.Registered
                    } else {
                        BleConnectionState.Disconnected
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectingInFlight = false
                    connectedHost = null
                    stopKeepAlive()
                    _connectionState.value = if (registered) {
                        BleConnectionState.Registered
                    } else {
                        BleConnectionState.Disconnected
                    }
                    if (registered) {
                        scheduleReconnectAfterDrop(device)
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            HidDebugLog.i("onGetReport type=$type id=$id buffer=$bufferSize")
            val hid = hidDevice ?: return
            val payload = when (type) {
                BluetoothHidDevice.REPORT_TYPE_INPUT -> EMPTY_INPUT_REPORT
                else -> ByteArray(bufferSize.coerceIn(0, 64).coerceAtLeast(1))
            }
            hid.replyReport(device, type, id, payload)
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            HidDebugLog.i("onSetReport type=$type id=$id len=${data.size}")
        }

        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            HidDebugLog.i("onSetProtocol protocol=$protocol")
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            HidDebugLog.i("onInterruptData reportId=$reportId len=${data.size}")
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            HidDebugLog.w("onVirtualCableUnplug ${device.name} — stay registered")
            connectingInFlight = false
            connectedHost = null
            stopKeepAlive()
            _connectionState.value = if (registered) {
                BleConnectionState.Registered
            } else {
                BleConnectionState.Disconnected
            }
            if (registered) {
                scheduleReconnectAfterDrop(device)
            }
        }
    }

    init {
        HidDebugLog.i("BleHidManager init")
        registerBondReceiver()
        refreshBondedDevices()
        connectHidProfile()
    }

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(bondReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(bondReceiver, filter)
            }
            bondReceiverRegistered = true
        } catch (e: Exception) {
            HidDebugLog.w("bond receiver: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun connectHidProfile() {
        if (!isSupported) {
            _connectionState.value = BleConnectionState.Error("Bluetooth недоступен")
            HidDebugLog.e("Bluetooth adapter missing")
            return
        }
        HidDebugLog.i("Requesting HID_DEVICE profile proxy")
        bluetoothAdapter?.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    fun register(hostActivity: Activity? = null): Boolean {
        val device = hidDevice
        if (device == null) {
            _connectionState.value = BleConnectionState.Error("HID-профиль ещё не готов")
            HidDebugLog.e("register(): hidDevice is null (profileReady=$profileReady)")
            connectHidProfile()
            return false
        }
        if (registered) {
            HidDebugLog.i("register(): already registered — rename + discoverable + connect last host")
            applyHidAdapterName()
            requestDiscoverable(hostActivity)
            tryHiddenDiscoverable()
            if (connectedHost == null && !connectingInFlight) {
                connectPersistedHost()
            }
            return true
        }
        if (registrationInFlight) {
            HidDebugLog.i("register(): already in flight")
            return true
        }

        applyHidAdapterName()

        connectRetryCount = 0
        hadHostSession = false

        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            APP_NAME,
            APP_DESCRIPTION,
            APP_PROVIDER,
            BluetoothHidDevice.SUBCLASS1_MOUSE,
            MOUSE_REPORT_DESCRIPTOR,
        )
        // QoS as in Android CTS / WearMouse — null often fails against Windows hosts
        val outQos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            QOS_TOKEN_RATE,
            QOS_TOKEN_BUCKET_SIZE,
            QOS_PEAK_BANDWIDTH,
            QOS_LATENCY,
            BluetoothHidDeviceAppQosSettings.MAX,
        )

        HidDebugLog.i("registerApp()...")
        registrationInFlight = true
        _connectionState.value = BleConnectionState.Registering
        val success = device.registerApp(sdpSettings, null, outQos, hidExecutor, hidCallback)
        if (success) {
            HidDebugLog.i("registerApp() returned true — waiting onAppStatusChanged")
            requestDiscoverable(hostActivity)
            tryHiddenDiscoverable()
        } else {
            registrationInFlight = false
            pendingConnectDevice = null
            _connectionState.value = BleConnectionState.Error("Не удалось зарегистрировать HID-приложение")
            HidDebugLog.e("registerApp() returned false — телефон может не поддерживать HID Device")
        }
        return success
    }

    /** Makes the phone briefly discoverable so Windows can refresh HID SDP. */
    private fun requestDiscoverable(hostActivity: Activity?) {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        try {
            if (hostActivity != null) {
                hostActivity.startActivity(intent)
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
            }
            HidDebugLog.i("Requested discoverable (300s) for HID SDP refresh")
        } catch (e: Exception) {
            HidDebugLog.w("Discoverable request failed: ${e.message}")
        }
    }

    /**
     * Connect to host. If HID is not registered yet, queues the device and starts registration.
     * Blocks duplicate connects while CONNECTING / registration in flight for same flow.
     */
    @SuppressLint("MissingPermission")
    fun connectToHostAddress(address: String): Boolean {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            HidDebugLog.e("connectToHostAddress(): no adapter")
            return false
        }
        val mac = address.trim().uppercase()
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            HidDebugLog.e("connectToHostAddress(): bad MAC $address")
            return false
        }
        persistLastHost(mac)
        val device = adapter.getRemoteDevice(mac)
        HidDebugLog.i(
            "connectToHostAddress $mac bond=${bondName(device.bondState)} name=${device.name ?: "?"}",
        )
        return connectToHost(device)
    }

    fun lastHostOrDefault(): String {
        val saved = prefs.getString(PREF_LAST_HOST, null)
        return if (saved != null && BluetoothAdapter.checkBluetoothAddress(saved)) {
            saved
        } else {
            DEFAULT_HOST_ADDRESS
        }
    }

    @SuppressLint("MissingPermission")
    fun forgetAddress(address: String): Boolean {
        val device = bluetoothAdapter?.bondedDevices?.firstOrNull { it.address == address }
            ?: return false
        return forgetDevice(device)
    }

    @SuppressLint("MissingPermission")
    fun connectToHost(device: BluetoothDevice): Boolean {
        val label = device.name ?: device.address
        when {
            _connectionState.value is BleConnectionState.Connected -> {
                val current = connectedHost
                if (current?.address == device.address) {
                    HidDebugLog.i("connectToHost(): already connected — small proof nudge")
                    nudgeCursorProof()
                    startKeepAlive()
                    return true
                }
                HidDebugLog.w("connectToHost(): already connected to another host")
                return false
            }
            connectingInFlight || _connectionState.value is BleConnectionState.Connecting -> {
                HidDebugLog.w("connectToHost(): already connecting — ignore ($label)")
                return false
            }
        }

        proofNudgePending = true
        if (!registered) {
            pendingConnectDevice = device
            HidDebugLog.i("connectToHost(): queued until registered → $label")
            return register()
        }

        return performConnect(device)
    }

    @SuppressLint("MissingPermission")
    private fun flushPendingConnect() {
        val pending = pendingConnectDevice ?: return
        pendingConnectDevice = null
        if (connectingInFlight || connectedHost != null) {
            HidDebugLog.i("flushPendingConnect(): skip — already connecting/connected")
            return
        }
        HidDebugLog.i("flushPendingConnect() → ${pending.name ?: pending.address}")
        performConnect(pending)
    }

    @SuppressLint("MissingPermission")
    private fun performConnect(device: BluetoothDevice): Boolean {
        val hid = hidDevice
        if (hid == null) {
            HidDebugLog.e("performConnect(): hidDevice null")
            return false
        }
        if (!registered) {
            HidDebugLog.e("performConnect(): HID not registered")
            return false
        }
        if (connectingInFlight) {
            HidDebugLog.w("performConnect(): already connecting — ignore")
            return false
        }
        val already = hid.getConnectionState(device)
        if (already == BluetoothProfile.STATE_CONNECTED) {
            HidDebugLog.i("performConnect(): stack already CONNECTED — adopt ${device.address}")
            hadHostSession = true
            connectedHost = device
            persistLastHost(device.address)
            connectingInFlight = false
            _connectionState.value = BleConnectionState.Connected(device.name ?: device.address)
            onHidFullyConnected(nudge = true)
            return true
        }

        val label = device.name ?: device.address
        logHostDiagnostics(device)
        persistLastHost(device.address)
        connectingInFlight = true
        _connectionState.value = BleConnectionState.Connecting(label)
        pendingConnectDevice = device
        HidDebugLog.i("connect() → $label (bond=${bondName(device.bondState)})")
        val ok = hid.connect(device)
        if (!ok) {
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                HidDebugLog.w("connect() false — createBond() then retry")
                val bonding = try {
                    device.createBond()
                } catch (e: Exception) {
                    HidDebugLog.w("createBond threw: ${e.message}")
                    false
                }
                if (bonding || device.bondState == BluetoothDevice.BOND_BONDING) {
                    return true
                }
            }
            connectingInFlight = false
            pendingConnectDevice = null
            _connectionState.value = BleConnectionState.Registered
            HidDebugLog.e("connect() returned false for $label")
        }
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun connectAnyKnownHost() {
        val hid = hidDevice ?: return
        val known = hid.getDevicesMatchingConnectionStates(
            intArrayOf(
                BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED,
            ),
        )
        HidDebugLog.i("HID known devices: ${known.size}")
        known.forEach { d ->
            HidDebugLog.i(
                "  hid-dev ${d.name ?: "?"} ${d.address} state=${connectionStateName(hid.getConnectionState(d))}",
            )
        }
        val already = known.firstOrNull {
            hid.getConnectionState(it) == BluetoothProfile.STATE_CONNECTED
        }
        if (already != null) {
            connectedHost = already
            connectingInFlight = false
            _connectionState.value = BleConnectionState.Connected(already.name ?: already.address)
            return
        }
        val candidate = known.firstOrNull()
        if (candidate != null && connectedHost == null && !connectingInFlight) {
            HidDebugLog.i("auto-connect HID candidate ${candidate.name ?: candidate.address}")
            performConnect(candidate)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectPersistedHost() {
        val addr = lastHostOrDefault()
        HidDebugLog.i("phone-initiated connect → $addr (getRemoteDevice; bond not required)")
        connectToHostAddress(addr)
    }

    private fun scheduleConnectRetry() {
        if (hadHostSession) {
            HidDebugLog.i("skip hammer retry — host already paired; wait for incoming HID")
            return
        }
        if (connectRetryCount >= MAX_CONNECT_RETRIES) {
            HidDebugLog.w("hid.connect retries exhausted — Windows: Добавить устройство → ArduHUD Mouse")
            return
        }
        connectRetryCount += 1
        val n = connectRetryCount
        scope.launch {
            delay(CONNECT_RETRY_MS)
            if (!registered || connectedHost != null || connectingInFlight) return@launch
            HidDebugLog.i("retry hid.connect #$n → ${lastHostOrDefault()}")
            connectPersistedHost()
        }
    }

    private fun scheduleReconnectAfterDrop(device: BluetoothDevice?) {
        reopenJob?.cancel()
        reopenJob = scope.launch {
            delay(REOPEN_AFTER_DROP_MS)
            if (!registered || connectedHost != null || connectingInFlight) return@launch
            if (restoreIfStackConnected(device)) return@launch
            HidDebugLog.i("HID still down after ${REOPEN_AFTER_DROP_MS}ms — one reopen connect")
            connectPersistedHost()
        }
    }

    @SuppressLint("MissingPermission")
    private fun restoreIfStackConnected(device: BluetoothDevice?): Boolean {
        val hid = hidDevice ?: return false
        val candidates = buildList {
            if (device != null) add(device)
            addAll(
                hid.getDevicesMatchingConnectionStates(
                    intArrayOf(BluetoothProfile.STATE_CONNECTED),
                ),
            )
        }
        val live = candidates.firstOrNull {
            hid.getConnectionState(it) == BluetoothProfile.STATE_CONNECTED
        } ?: return false
        HidDebugLog.i("stack still CONNECTED to ${live.address} — restore session")
        connectedHost = live
        hadHostSession = true
        persistLastHost(live.address)
        _connectionState.value = BleConnectionState.Connected(live.name ?: live.address)
        onHidFullyConnected(nudge = false)
        return true
    }

    private fun onHidFullyConnected(nudge: Boolean) {
        proofNudgePending = false
        if (nudge) nudgeCursorProof()
        startKeepAlive()
    }

    /** Tiny +X proof on explicit connect — pad gestures are the real mouse. */
    private fun nudgeCursorProof() {
        scope.launch {
            delay(80)
            if (connectedHost == null) return@launch
            HidDebugLog.i("proof nudge +X")
            repeat(3) {
                sendMouseMove(12, 0)
                delay(16)
            }
        }
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            delay(KEEP_ALIVE_MS)
            while (connectedHost != null && registered) {
                sendReport(EMPTY_INPUT_REPORT)
                delay(KEEP_ALIVE_MS)
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    @SuppressLint("MissingPermission")
    private fun forbidAudioProfiles(device: BluetoothDevice) {
        listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).forEach { profile ->
            try {
                bluetoothAdapter?.getProfileProxy(
                    appContext,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(p: Int, proxy: BluetoothProfile?) {
                            if (proxy == null) return
                            try {
                                if (!forbidProfileOnProxy(proxy, device, p)) {
                                    HidDebugLog.w("audio policy $p not applied for ${device.address}")
                                }
                            } catch (e: Exception) {
                                HidDebugLog.w("setConnectionPolicy($p): ${e.cause?.message ?: e.message}")
                            }
                            bluetoothAdapter.closeProfileProxy(p, proxy)
                        }

                        override fun onServiceDisconnected(p: Int) = Unit
                    },
                    profile,
                )
            } catch (e: Exception) {
                HidDebugLog.w("forbidAudioProfiles($profile): ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun unregister() {
        HidDebugLog.i("unregister()")
        pendingConnectDevice = null
        connectingInFlight = false
        registrationInFlight = false
        proofNudgePending = false
        hidDevice?.unregisterApp()
        registered = false
        connectedHost = null
        restoreAdapterName()
        _connectionState.value = BleConnectionState.Disconnected
    }

    @SuppressLint("MissingPermission")
    fun disconnectHost() {
        val host = connectedHost ?: return
        val who = Throwable().stackTraceToString().lineSequence().drop(1).take(6).joinToString(" | ")
        HidDebugLog.i("disconnect() → ${host.name ?: host.address} from $who")
        hidDevice?.disconnect(host)
    }

    @SuppressLint("MissingPermission")
    fun refreshBondedDevices() {
        val devices = bluetoothAdapter?.bondedDevices.orEmpty().map { d ->
            val major = d.bluetoothClass?.majorDeviceClass
            val name = d.name ?: d.address
            val likelyHost = major == android.bluetooth.BluetoothClass.Device.Major.COMPUTER ||
                name.contains("-PC", ignoreCase = true) ||
                name.contains("DESKTOP", ignoreCase = true) ||
                name.contains("LAPTOP", ignoreCase = true)
            HidBondedHost(name = name, address = d.address, likelyHost = likelyHost)
        }.sortedWith(
            compareByDescending<HidBondedHost> { it.likelyHost }
                .thenBy {
                    when {
                        it.name.contains("airmouse", ignoreCase = true) -> 2
                        it.name.contains("mouse", ignoreCase = true) -> 1
                        else -> 0
                    }
                }
                .thenBy { it.name.lowercase() },
        )
        val last = lastHostOrDefault()
        val lastKnown = devices.firstOrNull { it.address.equals(last, ignoreCase = true) }
        val withLast = if (lastKnown != null) {
            devices
        } else {
            val remoteName = try {
                bluetoothAdapter?.getRemoteDevice(last)?.name
            } catch (_: Exception) {
                null
            }
            listOf(
                HidBondedHost(
                    name = remoteName ?: "ZAVTRAK-PC",
                    address = last,
                    likelyHost = true,
                ),
            ) + devices
        }
        _bondedDevices.value = withLast
        HidDebugLog.i("Bonded devices: ${devices.size} (showing ${withLast.size}, lastHost=$last)")
        withLast.forEach { d ->
            HidDebugLog.i("  • ${d.name} / ${d.address} host=${d.likelyHost}")
        }
    }

    @SuppressLint("MissingPermission")
    fun forgetDevice(device: BluetoothDevice): Boolean {
        if (connectedHost?.address == device.address) {
            HidDebugLog.w("forgetDevice blocked while HID connected")
            return false
        }
        if (pendingConnectDevice?.address == device.address) {
            pendingConnectDevice = null
        }
        val removed = removeBond(device)
        refreshBondedDevices()
        return removed
    }

    @SuppressLint("MissingPermission")
    private fun removeBond(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod("removeBond")
            method.invoke(device) as Boolean
        } catch (_: Exception) {
            false
        }
    }

    fun sendMouseMove(dx: Int, dy: Int) {
        val clampedX = dx.coerceIn(-127, 127)
        val clampedY = dy.coerceIn(-127, 127)
        sendReport(byteArrayOf(0x00, clampedX.toByte(), clampedY.toByte(), 0x00))
    }

    fun sendScroll(delta: Int) {
        // Invert wheel for Windows HID only. ESP SCROLL lines stay untouched.
        val wheel = (-delta).coerceIn(-127, 127).toByte()
        sendReport(byteArrayOf(0x00, 0x00, 0x00, wheel))
    }

    fun sendMouseClick(button: MouseButton, pressed: Boolean) {
        val buttons = if (pressed) button.mask else 0x00
        sendReport(byteArrayOf(buttons, 0x00, 0x00, 0x00))
    }

    fun sendClick() {
        scope.launch {
            sendMouseClick(MouseButton.LEFT, true)
            delay(CLICK_HOLD_MS)
            sendMouseClick(MouseButton.LEFT, false)
        }
    }

    fun sendRightClick() {
        scope.launch {
            sendMouseClick(MouseButton.RIGHT, true)
            delay(CLICK_HOLD_MS)
            sendMouseClick(MouseButton.RIGHT, false)
        }
    }

    fun sendExtraButtonClick(button: MouseButton) {
        scope.launch {
            sendMouseClick(button, true)
            delay(CLICK_HOLD_MS)
            sendMouseClick(button, false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendReport(report: ByteArray) {
        val host = connectedHost ?: return
        val ok = hidDevice?.sendReport(host, REPORT_ID, report) ?: false
        if (!ok) {
            HidDebugLog.w("sendReport failed")
        } else if (report[1] != 0.toByte() || report[2] != 0.toByte()) {
            HidDebugLog.i("sendReport MOVE ${report[1].toInt()},${report[2].toInt()} ok")
        } else if (report[0] != 0.toByte()) {
            HidDebugLog.i("sendReport BUTTON ${report[0].toInt()} ok")
        } else if (report[3] != 0.toByte()) {
            HidDebugLog.i("sendReport WHEEL ${report[3].toInt()} ok")
        }
    }

    private fun forbidProfileOnProxy(
        proxy: BluetoothProfile,
        device: BluetoothDevice,
        profile: Int,
    ): Boolean {
        val policy = try {
            proxy.javaClass.getMethod(
                "setConnectionPolicy",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType,
            ).invoke(proxy, device, CONNECTION_POLICY_FORBIDDEN)
            true
        } catch (_: Exception) {
            false
        }
        if (policy) {
            HidDebugLog.i("audio profile $profile FORBIDDEN for ${device.address}")
            return true
        }
        return try {
            proxy.javaClass.getMethod(
                "setPriority",
                BluetoothDevice::class.java,
                Int::class.javaPrimitiveType,
            ).invoke(proxy, device, PRIORITY_OFF)
            HidDebugLog.i("audio profile $profile PRIORITY_OFF for ${device.address}")
            true
        } catch (e: Exception) {
            HidDebugLog.w("setPriority($profile): ${e.cause?.message ?: e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun applyHidAdapterName() {
        val adapter = bluetoothAdapter ?: return
        val current = adapter.name ?: return
        val savedOriginal = prefs.getString(PREF_ORIGINAL_NAME, null)
        if (current != APP_NAME) {
            if (savedOriginal.isNullOrBlank()) {
                prefs.edit().putString(PREF_ORIGINAL_NAME, current).apply()
            }
            originalAdapterName = current
            val ok = adapter.setName(APP_NAME)
            HidDebugLog.i("setName(\"$APP_NAME\") ok=$ok (was \"$current\")")
        } else {
            originalAdapterName = savedOriginal
            HidDebugLog.i("adapter name already \"$APP_NAME\"")
        }
    }

    @SuppressLint("MissingPermission")
    private fun restoreAdapterName() {
        val saved = originalAdapterName ?: prefs.getString(PREF_ORIGINAL_NAME, null) ?: return
        val adapter = bluetoothAdapter ?: return
        if (adapter.name != saved) {
            val ok = adapter.setName(saved)
            HidDebugLog.i("restore setName(\"$saved\") ok=$ok")
        }
        originalAdapterName = null
    }

    private fun persistLastHost(address: String) {
        val mac = address.trim().uppercase()
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) return
        if (prefs.getString(PREF_LAST_HOST, null) == mac) return
        prefs.edit().putString(PREF_LAST_HOST, mac).apply()
        HidDebugLog.i("persisted last HID host $mac")
    }

    @SuppressLint("MissingPermission")
    private fun tryHiddenDiscoverable() {
        try {
            val adapter = bluetoothAdapter ?: return
            val method = BluetoothAdapter::class.java.getMethod(
                "setScanMode",
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            )
            val ok = method.invoke(
                adapter,
                BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE,
                120_000L,
            )
            HidDebugLog.i("setScanMode(DISCOVERABLE, 120s) hidden API → $ok")
        } catch (e: Exception) {
            try {
                val adapter = bluetoothAdapter ?: return
                val method = BluetoothAdapter::class.java.getMethod(
                    "setScanMode",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                val ok = method.invoke(
                    adapter,
                    BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE,
                    120,
                )
                HidDebugLog.i("setScanMode(DISCOVERABLE, 120) int hidden API → $ok")
            } catch (e2: Exception) {
                HidDebugLog.w("hidden setScanMode: ${e.message}; ${e2.message}")
            }
        }
    }

    private fun extraBluetoothDevice(intent: Intent?): BluetoothDevice? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private fun bondName(state: Int): String {
        return when (state) {
            BluetoothDevice.BOND_BONDED -> "BONDED"
            BluetoothDevice.BOND_BONDING -> "BONDING"
            BluetoothDevice.BOND_NONE -> "NONE"
            else -> state.toString()
        }
    }

    @SuppressLint("MissingPermission")
    private fun logHostDiagnostics(device: BluetoothDevice) {
        val bond = when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> "BONDED"
            BluetoothDevice.BOND_BONDING -> "BONDING"
            else -> "NONE"
        }
        val btClass = device.bluetoothClass?.deviceClass?.toString(16) ?: "?"
        val major = device.bluetoothClass?.majorDeviceClass?.toString(16) ?: "?"
        HidDebugLog.i("Host diag: bond=$bond major=0x$major class=0x$btClass addr=${device.address}")
    }

    fun shutdown() {
        stopKeepAlive()
        unregister()
        if (bondReceiverRegistered) {
            try {
                appContext.unregisterReceiver(bondReceiver)
            } catch (_: Exception) {
            }
            bondReceiverRegistered = false
        }
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        hidDevice = null
        hidExecutor.shutdown()
    }

    private fun connectionStateName(state: Int): String {
        return when (state) {
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            else -> state.toString()
        }
    }

    companion object {
        private const val APP_NAME = "ArduHUD Mouse"
        private const val APP_DESCRIPTION = "ArduHUD HID Mouse"
        private const val APP_PROVIDER = "ArduHUD"
        private const val CLICK_HOLD_MS = 50L
        private const val REPORT_ID = 1
        private const val PREFS = "arduhud_hid"
        private const val PREF_LAST_HOST = "last_host_mac"
        private const val PREF_ORIGINAL_NAME = "original_bt_name"

        /** Windows BT radio on this PC — used when the phone has no HID bond. */
        const val DEFAULT_HOST_ADDRESS = "4C:23:38:0C:F6:26"
        private const val CONNECT_AFTER_REGISTER_MS = 8_000L
        private const val CONNECT_RETRY_MS = 4_000L
        private const val REOPEN_AFTER_DROP_MS = 8_000L
        private const val KEEP_ALIVE_MS = 2_000L
        private const val MAX_CONNECT_RETRIES = 3
        private const val CONNECTION_POLICY_FORBIDDEN = 0
        private const val PRIORITY_OFF = 0

        @Volatile
        private var instance: BleHidManager? = null

        fun get(context: Context): BleHidManager {
            return instance ?: synchronized(this) {
                instance ?: BleHidManager(context.applicationContext).also { instance = it }
            }
        }

        // WearMouse / CTS QoS defaults for interrupt channel
        private const val QOS_TOKEN_RATE = 800
        private const val QOS_TOKEN_BUCKET_SIZE = 9
        private const val QOS_PEAK_BANDWIDTH = 0
        private const val QOS_LATENCY = 11250

        private val EMPTY_INPUT_REPORT = byteArrayOf(0x00, 0x00, 0x00, 0x00)

        /** Standard relative mouse: Report ID 1, 5 buttons, X/Y/Wheel. */
        private val MOUSE_REPORT_DESCRIPTOR = byteArrayOf(
            0x05, 0x01, // Usage Page (Generic Desktop)
            0x09, 0x02, // Usage (Mouse)
            0xA1.toByte(), 0x01, // Collection (Application)
            0x85.toByte(), REPORT_ID.toByte(), // Report ID
            0x09, 0x01, // Usage (Pointer)
            0xA1.toByte(), 0x00, // Collection (Physical)
            0x05, 0x09, // Usage Page (Buttons)
            0x19, 0x01,
            0x29, 0x05,
            0x15, 0x00,
            0x25, 0x01,
            0x95.toByte(), 0x05,
            0x75, 0x01,
            0x81.toByte(), 0x02,
            0x95.toByte(), 0x01,
            0x75, 0x03,
            0x81.toByte(), 0x03, // 3-bit padding
            0x05, 0x01,
            0x09, 0x30, // X
            0x09, 0x31, // Y
            0x09, 0x38, // Wheel
            0x15, 0x81.toByte(),
            0x25, 0x7F,
            0x75, 0x08,
            0x95.toByte(), 0x03,
            0x81.toByte(), 0x06,
            0xC0.toByte(),
            0xC0.toByte(),
        )
    }
}
