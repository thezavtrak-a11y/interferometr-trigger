package com.example.arduhud.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import com.example.arduhud.link.EspLinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.BufferOverflow
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SoftAP + TCP line protocol.
 * Reader uses a blocking thread; writers use another IO coroutine + mutex.
 * (A single-thread dispatcher would stall TX behind readLine.)
 */
class EspWifiLinkManager(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()

    private var readerJob: Job? = null
    private var connectJob: Job? = null

    private var boundNetwork: Network? = null
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private val connecting = AtomicBoolean(false)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            appendLog("Wi-Fi SoftAP available")
            boundNetwork = network
            connectivityManager.bindProcessToNetwork(network)
            openTcp(network)
        }

        override fun onUnavailable() {
            connecting.set(false)
            _connectionState.value = EspLinkState.Error("Сеть ${AP_SSID} недоступна")
            appendLog("SoftAP unavailable — join ${AP_SSID} / pass ${AP_PASSWORD}")
        }

        override fun onLost(network: Network) {
            appendLog("SoftAP lost")
            scope.launch {
                cleanupSocket("Wi-Fi lost")
                _connectionState.value = EspLinkState.Disconnected
            }
        }
    }

    private val _connectionState = MutableStateFlow<EspLinkState>(EspLinkState.Disconnected)
    val connectionState: StateFlow<EspLinkState> = _connectionState.asStateFlow()

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

    private val _lastPos = MutableStateFlow<Pair<Int, Int>?>(null)
    val lastPos: StateFlow<Pair<Int, Int>?> = _lastPos.asStateFlow()

    fun connect() {
        if (connecting.getAndSet(true)) {
            appendLog("Connect already in progress")
            return
        }
        if (_connectionState.value is EspLinkState.Connected) {
            connecting.set(false)
            appendLog("Already connected")
            return
        }

        scope.launch {
            disconnectInternal(unbind = true)
            _connectionState.value = EspLinkState.Connecting
            appendLog("Requesting SoftAP ${AP_SSID} → TCP ${AP_HOST}:${AP_PORT}")

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                connecting.set(false)
                _connectionState.value = EspLinkState.Error("Нужен Android 10+ для SoftAP")
                return@launch
            }

            try {
                val specifier = WifiNetworkSpecifier.Builder()
                    .setSsid(AP_SSID)
                    .setWpa2Passphrase(AP_PASSWORD)
                    .build()
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build()
                withContext(Dispatchers.Main) {
                    connectivityManager.requestNetwork(request, networkCallback)
                }
                appendLog("System Wi-Fi dialog should appear — allow ${AP_SSID}")
            } catch (e: Exception) {
                connecting.set(false)
                _connectionState.value = EspLinkState.Error(e.message ?: "Wi-Fi request failed")
                appendLog("requestNetwork failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    fun disconnect() {
        appendLog("Disconnect requested")
        scope.launch {
            disconnectInternal(unbind = true)
            _connectionState.value = EspLinkState.Disconnected
        }
    }

    fun clearLog() {
        _logLines.value = emptyList()
    }

    fun sendPing(): Boolean = sendCommand("PING")

    fun sendClick(): Boolean = sendCommand("CLICK")

    fun sendAbs(x: Int, y: Int): Boolean = sendCommand("ABS $x $y")

    fun requestPos(): Boolean = sendCommand("GET_POS")

    fun sendText(text: String): Boolean = sendCommand(text)

    /** Awaitable TX on IO — ViewModel is Main; socket write on Main is dropped (NET on main). */
    suspend fun sendTextSuspend(text: String): Boolean =
        withContext(Dispatchers.IO) { writeLine(text) }

    /** Wait for a RX line prefix (subscribe before TX to avoid missing fast ACK). */
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

    private fun handleIncoming(text: String) {
        _rxLines.tryEmit(text)
        when {
            text.startsWith("PEN_MODE ") -> {
                val on = text.removePrefix("PEN_MODE ").trim() == "1"
                _penControlEnabled.value = on
                _linkEvents.tryEmit(EspLinkEvent.PenMode(on))
            }
            text.startsWith("POS ") -> {
                val parts = text.removePrefix("POS ").trim().split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val x = parts[0].toIntOrNull()
                    val y = parts[1].toIntOrNull()
                    if (x != null && y != null) {
                        val pos = x.coerceIn(0, 65535) to y.coerceIn(0, 65535)
                        _lastPos.value = pos
                        _linkEvents.tryEmit(EspLinkEvent.Pos(pos.first, pos.second))
                    }
                }
            }
            text.startsWith("ACK ABS ") || text.startsWith("ACK CLICK_AT ") -> {
                val parts = text.split(Regex("\\s+"))
                // ACK ABS x y BLE  /  ACK CLICK_AT x y BLE
                if (parts.size >= 4) {
                    val x = parts[2].toIntOrNull()
                    val y = parts[3].toIntOrNull()
                    if (x != null && y != null) {
                        _lastPos.value = x.coerceIn(0, 65535) to y.coerceIn(0, 65535)
                    }
                }
            }
        }
    }

    fun isConnected(): Boolean = connectionState.value is EspLinkState.Connected

    fun shutdown() {
        scope.launch {
            disconnectInternal(unbind = true)
        }
        scope.coroutineContext[Job]?.cancel()
    }

    private fun openTcp(network: Network) {
        connectJob?.cancel()
        connectJob = scope.launch {
            try {
                val sock = network.socketFactory.createSocket()
                sock.tcpNoDelay = true
                sock.keepAlive = true
                sock.soTimeout = READ_IDLE_MS
                try {
                    sock.setPerformancePreferences(0, 1, 2) // latency first
                } catch (_: Exception) {
                }
                sock.connect(InetSocketAddress(AP_HOST, AP_PORT), CONNECT_TIMEOUT_MS)

                writeMutex.withLock {
                    socket = sock
                    output = sock.getOutputStream()
                }

                connecting.set(false)
                _connectionState.value = EspLinkState.Connected("$AP_SSID @ $AP_HOST:$AP_PORT")
                appendLog("TCP connected")
                startReader(sock)
                writeLine("HELLO PHONE")
            } catch (e: Exception) {
                connecting.set(false)
                appendLog("TCP failed: ${e.javaClass.simpleName}: ${e.message}")
                _connectionState.value = EspLinkState.Error("TCP: ${e.message ?: "ошибка"}")
                cleanupSocket(null)
            }
        }
    }

    private fun startReader(sock: Socket) {
        readerJob?.cancel()
        // Byte-level reader: avoid BufferedReader waiting for a large fill before delivering lines.
        readerJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(256)
            val line = StringBuilder(64)
            try {
                val input = sock.getInputStream()
                while (isActive) {
                    val n = try {
                        input.read(buf)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }
                    if (n < 0) break
                    for (i in 0 until n) {
                        val ch = buf[i].toInt().toChar()
                        when (ch) {
                            '\n' -> {
                                val text = line.toString().trim('\r')
                                line.setLength(0)
                                if (text.isNotEmpty()) {
                                    appendLog("RX: $text")
                                    handleIncoming(text)
                                }
                            }
                            '\r' -> Unit
                            else -> if (line.length < 200) line.append(ch)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    appendLog("Reader error: ${e.javaClass.simpleName}: ${e.message}")
                }
            } finally {
                if (_connectionState.value is EspLinkState.Connected) {
                    _connectionState.value = EspLinkState.Disconnected
                    appendLog("TCP closed")
                }
            }
        }
    }

    private fun sendCommand(command: String): Boolean {
        if (_connectionState.value !is EspLinkState.Connected) {
            appendLog("TX skipped, not connected")
            return false
        }
        scope.launch {
            writeLine(command)
        }
        return true
    }

    private suspend fun writeLine(command: String): Boolean {
        val payload = command.trim() + "\n"
        return try {
            writeMutex.withLock {
                val out = output
                val sock = socket
                if (out == null || sock == null || sock.isClosed || !sock.isConnected) {
                    appendLog("TX skipped, socket not ready")
                    return false
                }
                out.write(payload.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
            appendLog("TX: ${command.trim()}")
            true
        } catch (e: Exception) {
            appendLog("Write failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private suspend fun disconnectInternal(unbind: Boolean) {
        connecting.set(false)
        readerJob?.cancel()
        readerJob = null
        connectJob?.cancel()
        connectJob = null
        cleanupSocket(null)
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        if (unbind) {
            try {
                connectivityManager.bindProcessToNetwork(null)
            } catch (_: Exception) {
            }
            boundNetwork = null
        }
    }

    private suspend fun cleanupSocket(reason: String?) {
        writeMutex.withLock {
            try {
                output?.flush()
            } catch (_: Exception) {
            }
            output = null
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            socket = null
        }
        if (reason != null) {
            appendLog(reason)
        }
    }

    private fun appendLog(message: String) {
        val line = "${timestamp()}  $message"
        val updated = (_logLines.value + line).takeLast(MAX_LOG_LINES)
        _logLines.value = updated
    }

    private fun timestamp(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    companion object {
        const val AP_SSID = "ArduHUD-ESP"
        const val AP_PASSWORD = "arduhud123"
        const val AP_HOST = "192.168.4.1"
        const val AP_PORT = 3333
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_IDLE_MS = 250
        private const val MAX_LOG_LINES = 200
    }
}
