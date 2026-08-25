package com.example.arduhud.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.arduhud.AppViewModel
import com.example.arduhud.LinkTransport
import com.example.arduhud.R
import com.example.arduhud.link.EspLinkState
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import kotlin.math.max

class ConnectionFragment : Fragment() {

    private val viewModel: AppViewModel by activityViewModels()

    private lateinit var wifiStatusText: TextView
    private lateinit var wifiCredsText: TextView
    private lateinit var usbStatusText: TextView
    private lateinit var debugLogText: TextView
    private lateinit var customCommandInput: TextInputEditText
    private lateinit var connectWifiButton: MaterialButton
    private lateinit var disconnectWifiButton: MaterialButton
    private lateinit var connectUsbButton: MaterialButton
    private lateinit var disconnectUsbButton: MaterialButton
    private lateinit var keepScreenOnSwitch: SwitchMaterial
    private var updatingKeepScreenSwitch = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.fragment_connection, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        wifiStatusText = view.findViewById(R.id.wifiStatusText)
        wifiCredsText = view.findViewById(R.id.wifiCredsText)
        usbStatusText = view.findViewById(R.id.usbStatusText)
        debugLogText = view.findViewById(R.id.debugLogText)
        customCommandInput = view.findViewById(R.id.customCommandInput)
        connectWifiButton = view.findViewById(R.id.connectWifiButton)
        disconnectWifiButton = view.findViewById(R.id.disconnectWifiButton)
        connectUsbButton = view.findViewById(R.id.connectUsbButton)
        disconnectUsbButton = view.findViewById(R.id.disconnectUsbButton)
        keepScreenOnSwitch = view.findViewById(R.id.keepScreenOnSwitch)
        keepScreenOnSwitch.isChecked = viewModel.isKeepScreenOn()
        keepScreenOnSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingKeepScreenSwitch) return@setOnCheckedChangeListener
            viewModel.setKeepScreenOn(isChecked)
        }

        debugLogText.movementMethod = ScrollingMovementMethod.getInstance()
        debugLogText.setOnTouchListener { v, event ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }

        wifiCredsText.text = getString(
            R.string.wifi_creds,
            viewModel.wifiSsid,
            viewModel.wifiPassword,
            viewModel.wifiEndpoint,
        )

        connectWifiButton.setOnClickListener { viewModel.connectWifi() }
        disconnectWifiButton.setOnClickListener { viewModel.disconnectWifi() }
        connectUsbButton.setOnClickListener { viewModel.connectUsbOtg() }
        disconnectUsbButton.setOnClickListener { viewModel.disconnectUsb() }
        view.findViewById<MaterialButton>(R.id.refreshUsbButton).setOnClickListener {
            viewModel.refreshUsbDevices()
        }
        view.findViewById<MaterialButton>(R.id.sendPingButton).setOnClickListener {
            if (!viewModel.sendPing()) showMessage(getString(R.string.link_send_failed))
        }
        view.findViewById<MaterialButton>(R.id.sendClickButton).setOnClickListener {
            if (!viewModel.sendClick()) showMessage(getString(R.string.link_send_failed))
        }
        view.findViewById<MaterialButton>(R.id.sendCustomCommandButton).setOnClickListener {
            val command = customCommandInput.text?.toString().orEmpty().trim()
            if (command.isBlank()) {
                showMessage(getString(R.string.link_custom_command_empty))
                return@setOnClickListener
            }
            if (viewModel.sendCustomCommand(command)) {
                customCommandInput.setText("")
            } else {
                showMessage(getString(R.string.link_send_failed))
            }
        }
        view.findViewById<MaterialButton>(R.id.copyLogButton).setOnClickListener {
            copyLogToClipboard()
        }
        view.findViewById<MaterialButton>(R.id.clearLogButton).setOnClickListener {
            viewModel.clearLinkLog()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { state ->
                        updateConnectionUi(state, viewModel.activeTransport.value)
                    }
                }
                launch {
                    viewModel.activeTransport.collect { transport ->
                        updateConnectionUi(viewModel.connectionState.value, transport)
                    }
                }
                launch {
                    viewModel.debugLog.collect { lines ->
                        debugLogText.text = if (lines.isEmpty()) "—" else lines.joinToString("\n")
                        scrollLogToBottom()
                    }
                }
                launch {
                    viewModel.keepScreenOn.collect { enabled ->
                        if (keepScreenOnSwitch.isChecked != enabled) {
                            updatingKeepScreenSwitch = true
                            keepScreenOnSwitch.isChecked = enabled
                            updatingKeepScreenSwitch = false
                        }
                    }
                }
                launch {
                    viewModel.keepScreenOnAutoOffMessage.collect { msg ->
                        if (msg != null) {
                            showMessage(getString(R.string.keep_screen_on_auto_off))
                            viewModel.consumeKeepScreenOnAutoOffMessage()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        scrollLogToBottom()
    }

    private fun scrollLogToBottom() {
        debugLogText.post {
            val layout = debugLogText.layout
            if (layout == null) {
                debugLogText.post { scrollLogToBottom() }
                return@post
            }
            val scrollAmount = layout.getLineTop(debugLogText.lineCount) - debugLogText.height +
                debugLogText.paddingTop + debugLogText.paddingBottom
            debugLogText.scrollTo(0, max(0, scrollAmount))
        }
    }

    private fun updateConnectionUi(state: EspLinkState, transport: LinkTransport) {
        val wifiActive = transport == LinkTransport.Wifi
        val usbActive = transport == LinkTransport.Usb

        wifiStatusText.text = when {
            wifiActive && state is EspLinkState.Connected ->
                getString(R.string.wifi_status_connected, state.endpoint)
            wifiActive && state == EspLinkState.RequestingPermission ->
                getString(R.string.wifi_status_permission)
            wifiActive && state == EspLinkState.Connecting ->
                getString(R.string.wifi_status_connecting)
            wifiActive && state is EspLinkState.Error -> state.message
            else -> getString(R.string.wifi_status_disconnected)
        }

        usbStatusText.text = when {
            usbActive && state is EspLinkState.Connected ->
                getString(R.string.usb_status_connected, state.endpoint)
            usbActive && state == EspLinkState.RequestingPermission ->
                getString(R.string.usb_status_permission)
            usbActive && state == EspLinkState.Connecting ->
                getString(R.string.usb_status_connecting)
            usbActive && state is EspLinkState.Error -> state.message
            else -> getString(R.string.usb_status_disconnected)
        }

        val connected = state is EspLinkState.Connected
        val busy = state == EspLinkState.Connecting || state == EspLinkState.RequestingPermission
        connectWifiButton.isEnabled = !(wifiActive && (connected || busy))
        disconnectWifiButton.isEnabled = wifiActive && (connected || busy)
        connectUsbButton.isEnabled = !(usbActive && (connected || busy))
        disconnectUsbButton.isEnabled = usbActive && (connected || busy)
    }

    private fun copyLogToClipboard() {
        val text = debugLogText.text?.toString().orEmpty()
        if (text.isBlank() || text == "—") {
            showMessage(getString(R.string.log_empty))
            return
        }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ArduHUD Wi-Fi log", text))
        showMessage(getString(R.string.log_copied))
    }

    private fun showMessage(message: String) {
        view?.let { Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show() }
    }
}
