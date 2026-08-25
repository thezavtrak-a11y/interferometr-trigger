package com.example.arduhud.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.arduhud.link.EspLinkState
import com.example.arduhud.wifi.EspLinkEvent
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * USB OTG CDC serial — same newline protocol as SoftAP TCP ([PROTOCOL.md]).
 */
class EspUsbSerialManager(context: Context) {

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()

    private val _connectionState = MutableStateFlow<EspLinkState>(EspLinkState.Disconnected)
    val connectionState: StateFlow<EspLinkState> = _connectionState.asStateFlow()

    private val _devices = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    val devices: StateFlow<List<UsbDeviceInfo>> = _devices.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private val _linkEvents = MutableSharedFlow<EspLinkEvent>(extraBufferCapacity = 32)
    val linkEvents: SharedFlow<EspLinkEvent> = _linkEvents.asSharedFlow()

    private val _rxLines = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val _penControlEnabled = MutableStateFlow(true)
    val penControlEnabled: StateFlow<Boolean> = _penControlEnabled.asStateFlow()

    private var activePort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var permissionReceiverRegistered = false
    private val rxLineBuf = StringBuilder(128)

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return
            val device = intent.usbDeviceExtra() ?: return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                openDevice(device)
            } else {
                appendLog("USB permission denied for ${device.deviceName}")
                _connectionState.value = EspLinkState.Error("Нет доступа к USB-устройству")
            }
        }
    }

    init {
        registerPermissionReceiver()
        refreshDevices()
    }

    fun refreshDevices() {
        val foundDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        _devices.value = foundDrivers.map { driver ->
            UsbDeviceInfo(
                deviceId = driver.device.deviceId,
                deviceName = friendlyName(driver.device),
                vendorId = driver.device.vendorId,
                productId = driver.device.productId,
            )
        }
        if (_devices.value.isEmpty()) {
            appendLog("USB OTG: нет serial-устройств (CDC ESP?)")
        } else {
            appendLog("USB OTG: найдено ${_devices.value.size}")
        }
    }

    fun connectFirstDevice() {
        refreshDevices()
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()
        if (driver == null) {
            _connectionState.value = EspLinkState.Error("USB serial не найден — воткните ESP через OTG")
            return
        }
        connectDriver(driver)
    }

    fun disconnect() {
        stopIo()
        _connectionState.value = EspLinkState.Disconnected
        appendLog("USB OTG отключён")
    }

    fun clearLog() {
        _logLines.value = emptyList()
    }

    fun sendPing(): Boolean = sendCommand("PING")

    fun sendClick(): Boolean = sendCommand("CLICK")

    fun sendText(text: String): Boolean = sendCommand(text)

    suspend fun sendTextSuspend(text: String): Boolean =
        withContext(Dispatchers.IO) { writeLine(text) }

    suspend fun awaitLineStartingWith(prefix: String, timeoutMs: Long): Boolean {
        return try {
            withTimeout(timeoutMs) {
                _rxLines.first { it.startsWith(prefix) }
            }
            true
        } catch (_: TimeoutCancellationException) {
            false
        }
    }

    fun isConnected(): Boolean = connectionState.value is EspLinkState.Connected

    fun shutdown() {
        disconnect()
        if (permissionReceiverRegistered) {
            appContext.unregisterReceiver(permissionReceiver)
            permissionReceiverRegistered = false
        }
    }

    private fun connectDriver(driver: com.hoho.android.usbserial.driver.UsbSerialDriver) {
        val device = driver.device
        if (!usbManager.hasPermission(device)) {
            requestPermission(device)
            return
        }
        openDevice(device)
    }

    private fun requestPermission(device: UsbDevice) {
        _connectionState.value = EspLinkState.RequestingPermission
        appendLog("USB: запрос разрешения ${friendlyName(device)}")
        val permissionIntent = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun openDevice(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            .firstOrNull { it.device.deviceId == device.deviceId }
        if (driver == null) {
            _connectionState.value = EspLinkState.Error("Драйвер USB serial не найден")
            return
        }

        _connectionState.value = EspLinkState.Connecting
        appendLog("USB: открытие ${friendlyName(device)}")

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            _connectionState.value = EspLinkState.Error("Не удалось открыть USB")
            return
        }

        val port = driver.ports.firstOrNull()
        if (port == null) {
            _connectionState.value = EspLinkState.Error("Нет serial-порта на устройстве")
            return
        }

        try {
            stopIo()
            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // ESP32-C3 CDC often needs DTR/RTS for the virtual COM to accept data.
            try {
                port.dtr = true
                port.rts = true
            } catch (_: Exception) {
            }
            activePort = port
            rxLineBuf.clear()
            ioManager = SerialInputOutputManager(
                port,
                object : SerialInputOutputManager.Listener {
                    override fun onNewData(data: ByteArray) {
                        for (b in data) {
                            val ch = b.toInt().toChar()
                            when (ch) {
                                '\n' -> {
                                    val text = rxLineBuf.toString().trim('\r')
                                    rxLineBuf.clear()
                                    if (text.isNotEmpty()) {
                                        appendLog("RX: $text")
                                        handleIncoming(text)
                                    }
                                }
                                '\r' -> Unit
                                else -> if (rxLineBuf.length < 200) rxLineBuf.append(ch)
                            }
                        }
                    }

                    override fun onRunError(e: Exception) {
                        appendLog("USB IO: ${e.message ?: e.javaClass.simpleName}")
                        _connectionState.value = EspLinkState.Error("Ошибка чтения USB")
                        stopIo()
                    }
                },
            ).also { it.start() }

            val name = friendlyName(device)
            _connectionState.value = EspLinkState.Connected("USB_OTG_COM @ $name")
            appendLog("USB OTG COM 115200 — HELLO")
            sendCommand("HELLO PHONE")
        } catch (e: Exception) {
            appendLog("Open failed: ${e.message ?: e.javaClass.simpleName}")
            _connectionState.value = EspLinkState.Error("Не удалось открыть serial-порт")
            stopIo()
        }
    }

    private fun handleIncoming(text: String) {
        _rxLines.tryEmit(text)
        when {
            text.startsWith("PEN_MODE ") || text.startsWith("TOUCH_MODE ") -> {
                val on = text.substringAfter(' ').trim() == "1"
                _penControlEnabled.value = on
                _linkEvents.tryEmit(EspLinkEvent.PenMode(on))
            }
            text.startsWith("POS ") -> {
                val parts = text.removePrefix("POS ").trim().split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val x = parts[0].toIntOrNull()
                    val y = parts[1].toIntOrNull()
                    if (x != null && y != null) {
                        _linkEvents.tryEmit(EspLinkEvent.Pos(x, y))
                    }
                }
            }
        }
    }

    private fun sendCommand(command: String): Boolean {
        if (_connectionState.value !is EspLinkState.Connected) {
            appendLog("TX skipped, USB not connected")
            return false
        }
        scope.launch { writeLine(command) }
        return true
    }

    private suspend fun writeLine(command: String): Boolean {
        val payload = command.trim() + "\n"
        return try {
            writeMutex.withLock {
                val port = activePort ?: run {
                    appendLog("TX skipped, port null")
                    return false
                }
                port.write(payload.toByteArray(StandardCharsets.UTF_8), WRITE_TIMEOUT_MS)
            }
            appendLog("TX: ${command.trim()}")
            true
        } catch (e: Exception) {
            appendLog("Write failed: ${e.message ?: e.javaClass.simpleName}")
            _connectionState.value = EspLinkState.Error("Ошибка записи USB")
            false
        }
    }

    private fun stopIo() {
        ioManager?.stop()
        ioManager = null
        try {
            activePort?.close()
        } catch (_: Exception) {
        }
        activePort = null
        rxLineBuf.clear()
    }

    private fun appendLog(message: String) {
        val updated = (_logLines.value + "${timestamp()}  $message").takeLast(MAX_LOG_LINES)
        _logLines.value = updated
    }

    private fun registerPermissionReceiver() {
        if (permissionReceiverRegistered) return
        ContextCompat.registerReceiver(
            appContext,
            permissionReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        permissionReceiverRegistered = true
    }

    private fun friendlyName(device: UsbDevice): String {
        val product = if (device.productName.isNullOrBlank()) "USB serial" else device.productName
        return "$product (VID=%04X PID=%04X)".format(device.vendorId, device.productId)
    }

    private fun timestamp(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun Intent.usbDeviceExtra(): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.arduhud.USB_PERMISSION"
        private const val MAX_LOG_LINES = 200
        private const val WRITE_TIMEOUT_MS = 1000
    }
}
