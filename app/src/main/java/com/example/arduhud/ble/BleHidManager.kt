package com.example.arduhud.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _bondedDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val bondedDevices: StateFlow<List<BluetoothDevice>> = _bondedDevices.asStateFlow()

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
                if (connectedHost == null && !connectingInFlight) {
                    _connectionState.value = BleConnectionState.Registered
                }
                flushPendingConnect()
            } else {
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
                    connectedHost = device
                    val name = device?.name ?: device?.address ?: "Unknown"
                    _connectionState.value = BleConnectionState.Connected(name)
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    connectingInFlight = true
                    _connectionState.value = BleConnectionState.Connecting(
                        device?.name ?: device?.address ?: "?",
                    )
                }
                BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_DISCONNECTING,
                -> {
                    connectingInFlight = false
                    connectedHost = null
                    _connectionState.value = if (registered) {
                        BleConnectionState.Registered
                    } else {
                        BleConnectionState.Disconnected
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            HidDebugLog.i("onGetReport type=$type id=$id buffer=$bufferSize")
            val hid = hidDevice ?: return
            when (type) {
                BluetoothHidDevice.REPORT_TYPE_FEATURE -> {
                    hid.replyReport(device, type, id, MOUSE_REPORT_DESCRIPTOR)
                }
                BluetoothHidDevice.REPORT_TYPE_INPUT -> {
                    hid.replyReport(device, type, id, EMPTY_INPUT_REPORT)
                }
                else -> {
                    hid.replyReport(device, type, id, EMPTY_INPUT_REPORT)
                }
            }
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
            HidDebugLog.w("onVirtualCableUnplug ${device.name}")
            connectingInFlight = false
            connectedHost = null
            _connectionState.value = if (registered) {
                BleConnectionState.Registered
            } else {
                BleConnectionState.Disconnected
            }
        }
    }

    init {
        HidDebugLog.i("BleHidManager init")
        refreshBondedDevices()
        connectHidProfile()
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
    fun register(): Boolean {
        val device = hidDevice
        if (device == null) {
            _connectionState.value = BleConnectionState.Error("HID-профиль ещё не готов")
            HidDebugLog.e("register(): hidDevice is null (profileReady=$profileReady)")
            connectHidProfile()
            return false
        }
        if (registered) {
            HidDebugLog.i("register(): already registered")
            return true
        }
        if (registrationInFlight) {
            HidDebugLog.i("register(): already in flight")
            return true
        }

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
            requestDiscoverable()
        } else {
            registrationInFlight = false
            pendingConnectDevice = null
            _connectionState.value = BleConnectionState.Error("Не удалось зарегистрировать HID-приложение")
            HidDebugLog.e("registerApp() returned false — телефон может не поддерживать HID Device")
        }
        return success
    }

    /** Makes the phone briefly discoverable so Windows can refresh HID SDP. */
    private fun requestDiscoverable() {
        try {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 60)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            HidDebugLog.i("Requested discoverable (60s) for HID SDP refresh")
        } catch (e: Exception) {
            HidDebugLog.w("Discoverable request failed: ${e.message}")
        }
    }

    /**
     * Connect to host. If HID is not registered yet, queues the device and starts registration.
     * Blocks duplicate connects while CONNECTING / registration in flight for same flow.
     */
    @SuppressLint("MissingPermission")
    fun connectToHost(device: BluetoothDevice): Boolean {
        val label = device.name ?: device.address
        when {
            _connectionState.value is BleConnectionState.Connected -> {
                val current = connectedHost
                if (current?.address == device.address) {
                    HidDebugLog.i("connectToHost(): already connected to $label")
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

        val label = device.name ?: device.address
        logHostDiagnostics(device)
        connectingInFlight = true
        _connectionState.value = BleConnectionState.Connecting(label)
        HidDebugLog.i("connect() → $label")
        val ok = hid.connect(device)
        if (!ok) {
            connectingInFlight = false
            _connectionState.value = BleConnectionState.Registered
            HidDebugLog.e("connect() returned false for $label")
        }
        return ok
    }

    @SuppressLint("MissingPermission")
    fun unregister() {
        HidDebugLog.i("unregister()")
        pendingConnectDevice = null
        connectingInFlight = false
        registrationInFlight = false
        hidDevice?.unregisterApp()
        registered = false
        connectedHost = null
        _connectionState.value = BleConnectionState.Disconnected
    }

    @SuppressLint("MissingPermission")
    fun disconnectHost() {
        val host = connectedHost ?: return
        HidDebugLog.i("disconnect() → ${host.name ?: host.address}")
        hidDevice?.disconnect(host)
    }

    @SuppressLint("MissingPermission")
    fun refreshBondedDevices() {
        val devices = bluetoothAdapter?.bondedDevices?.toList().orEmpty()
        _bondedDevices.value = devices
        HidDebugLog.i("Bonded devices: ${devices.size}")
        devices.forEach { d ->
            HidDebugLog.i("  • ${d.name ?: "?"} / ${d.address}")
        }
    }

    @SuppressLint("MissingPermission")
    fun forgetDevice(device: BluetoothDevice): Boolean {
        if (pendingConnectDevice?.address == device.address) {
            pendingConnectDevice = null
        }
        if (connectedHost?.address == device.address) {
            disconnectHost()
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
        val wheel = delta.coerceIn(-127, 127).toByte()
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
        unregister()
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
        private const val APP_DESCRIPTION = "ArduHUD BLE HID Mouse"
        private const val APP_PROVIDER = "ArduHUD"
        private const val CLICK_HOLD_MS = 50L
        private const val REPORT_ID = 1

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
