package com.example.arduhud.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.arduhud.AppViewModel
import com.example.arduhud.LinkTransport
import com.example.arduhud.MainActivity
import com.example.arduhud.R
import com.example.arduhud.ble.BleConnectionState
import com.example.arduhud.ble.HidBondedHost
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
    private lateinit var directBleStatusText: TextView
    private lateinit var bondedDevicesContainer: LinearLayout
    private lateinit var debugLogText: TextView
    private lateinit var customCommandInput: TextInputEditText
    private lateinit var connectWifiButton: MaterialButton
    private lateinit var disconnectWifiButton: MaterialButton
    private lateinit var connectUsbButton: MaterialButton
    private lateinit var disconnectUsbButton: MaterialButton
    private lateinit var registerHidButton: MaterialButton
    private lateinit var connectHidPcButton: MaterialButton
    private lateinit var openTouchpadButton: MaterialButton
    private lateinit var disconnectHidButton: MaterialButton
    private lateinit var keepScreenOnSwitch: SwitchMaterial
    private lateinit var connectionTypeHeader: View
    private lateinit var connectionTypeHeaderText: TextView
    private lateinit var connectionTypeChevron: ImageView
    private lateinit var connectionTypeList: View
    private lateinit var typeDirectBleButton: MaterialButton
    private lateinit var typeWifiButton: MaterialButton
    private lateinit var typeUsbButton: MaterialButton
    private lateinit var directBlePanel: View
    private lateinit var wifiPanel: View
    private lateinit var usbPanel: View
    private lateinit var espCommandPanel: View
    private var updatingKeepScreenSwitch = false

    /** UI picker only — not the live [AppViewModel.activeTransport]. */
    private var selectedType: LinkTransport? = null
    private var typeListExpanded = true

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
        directBleStatusText = view.findViewById(R.id.directBleStatusText)
        bondedDevicesContainer = view.findViewById(R.id.bondedDevicesContainer)
        debugLogText = view.findViewById(R.id.debugLogText)
        customCommandInput = view.findViewById(R.id.customCommandInput)
        connectWifiButton = view.findViewById(R.id.connectWifiButton)
        disconnectWifiButton = view.findViewById(R.id.disconnectWifiButton)
        connectUsbButton = view.findViewById(R.id.connectUsbButton)
        disconnectUsbButton = view.findViewById(R.id.disconnectUsbButton)
        registerHidButton = view.findViewById(R.id.registerHidButton)
        connectHidPcButton = view.findViewById(R.id.connectHidPcButton)
        openTouchpadButton = view.findViewById(R.id.openTouchpadButton)
        disconnectHidButton = view.findViewById(R.id.disconnectHidButton)
        keepScreenOnSwitch = view.findViewById(R.id.keepScreenOnSwitch)
        connectionTypeHeader = view.findViewById(R.id.connectionTypeHeader)
        connectionTypeHeaderText = view.findViewById(R.id.connectionTypeHeaderText)
        connectionTypeChevron = view.findViewById(R.id.connectionTypeChevron)
        connectionTypeList = view.findViewById(R.id.connectionTypeList)
        typeDirectBleButton = view.findViewById(R.id.typeDirectBleButton)
        typeWifiButton = view.findViewById(R.id.typeWifiButton)
        typeUsbButton = view.findViewById(R.id.typeUsbButton)
        directBlePanel = view.findViewById(R.id.directBlePanel)
        wifiPanel = view.findViewById(R.id.wifiPanel)
        usbPanel = view.findViewById(R.id.usbPanel)
        espCommandPanel = view.findViewById(R.id.espCommandPanel)

        restoreTypePicker(savedInstanceState)
        applyTypePickerUi()

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

        connectionTypeHeader.setOnClickListener {
            if (selectedType == null) return@setOnClickListener
            typeListExpanded = !typeListExpanded
            applyTypePickerUi()
        }
        typeDirectBleButton.setOnClickListener { pickConnectionType(LinkTransport.DirectBle) }
        typeWifiButton.setOnClickListener { pickConnectionType(LinkTransport.Wifi) }
        typeUsbButton.setOnClickListener { pickConnectionType(LinkTransport.Usb) }

        connectWifiButton.setOnClickListener { viewModel.connectWifi() }
        disconnectWifiButton.setOnClickListener { viewModel.disconnectWifi() }
        connectUsbButton.setOnClickListener { viewModel.connectUsbOtg() }
        disconnectUsbButton.setOnClickListener { viewModel.disconnectUsb() }
        registerHidButton.setOnClickListener {
            if (!viewModel.registerDirectBle(requireActivity())) {
                showMessage(getString(R.string.direct_ble_register_failed))
            } else {
                showMessage(getString(R.string.direct_ble_wait_host))
            }
        }
        connectHidPcButton.setOnClickListener {
            if (!viewModel.connectLastHidHost()) {
                showMessage(getString(R.string.link_send_failed))
            }
        }
        openTouchpadButton.setOnClickListener {
            (activity as? MainActivity)?.openTouchpad()
        }
        disconnectHidButton.setOnClickListener { viewModel.disconnectDirectBle() }
        view.findViewById<MaterialButton>(R.id.refreshBondedButton).setOnClickListener {
            viewModel.refreshBleHosts()
        }
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
        view.findViewById<MaterialButton>(R.id.showTutorialButton).setOnClickListener {
            (activity as? MainActivity)?.startTutorial()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect { state ->
                        updateConnectionUi(
                            state,
                            viewModel.activeTransport.value,
                            viewModel.bleUiState.value,
                        )
                    }
                }
                launch {
                    viewModel.activeTransport.collect { transport ->
                        updateConnectionUi(
                            viewModel.connectionState.value,
                            transport,
                            viewModel.bleUiState.value,
                        )
                    }
                }
                launch {
                    viewModel.bleUiState.collect { ble ->
                        updateConnectionUi(
                            viewModel.connectionState.value,
                            viewModel.activeTransport.value,
                            ble,
                        )
                    }
                }
                launch {
                    viewModel.bleHosts.collect { hosts ->
                        renderBondedHosts(hosts)
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedType?.let { outState.putString(STATE_SELECTED_TYPE, it.name) }
        outState.putBoolean(STATE_TYPE_LIST_EXPANDED, typeListExpanded)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshBleHosts()
        scrollLogToBottom()
    }

    private fun restoreTypePicker(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            selectedType = null
            typeListExpanded = true
            return
        }
        selectedType = savedInstanceState.getString(STATE_SELECTED_TYPE)
            ?.let { runCatching { LinkTransport.valueOf(it) }.getOrNull() }
            ?.takeIf { it != LinkTransport.None }
        typeListExpanded = if (selectedType == null) {
            true
        } else {
            savedInstanceState.getBoolean(STATE_TYPE_LIST_EXPANDED, false)
        }
    }

    private fun pickConnectionType(type: LinkTransport) {
        selectedType = type
        typeListExpanded = false
        viewModel.setPreferredTransport(type)
        applyTypePickerUi()
    }

    private fun applyTypePickerUi() {
        val selected = selectedType
        if (selected == null) {
            typeListExpanded = true
        }
        connectionTypeList.visibility = if (typeListExpanded) View.VISIBLE else View.GONE
        connectionTypeHeaderText.text = when (selected) {
            LinkTransport.DirectBle -> getString(R.string.connection_type_direct_ble)
            LinkTransport.Wifi -> getString(R.string.connection_type_wifi)
            LinkTransport.Usb -> getString(R.string.connection_type_usb)
            else -> getString(R.string.connection_type_label)
        }
        connectionTypeChevron.rotation = if (typeListExpanded) 180f else 0f
        connectionTypeHeader.isClickable = selected != null
        styleTypeChoice(typeDirectBleButton, selected == LinkTransport.DirectBle)
        styleTypeChoice(typeWifiButton, selected == LinkTransport.Wifi)
        styleTypeChoice(typeUsbButton, selected == LinkTransport.Usb)

        directBlePanel.visibility = if (selected == LinkTransport.DirectBle) View.VISIBLE else View.GONE
        wifiPanel.visibility = if (selected == LinkTransport.Wifi) View.VISIBLE else View.GONE
        usbPanel.visibility = if (selected == LinkTransport.Usb) View.VISIBLE else View.GONE
        espCommandPanel.visibility =
            if (selected == LinkTransport.Wifi || selected == LinkTransport.Usb) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun styleTypeChoice(button: MaterialButton, selected: Boolean) {
        val ctx = requireContext()
        if (selected) {
            button.backgroundTintList = ColorStateList.valueOf(ctx.getColor(R.color.metro_accent))
            button.strokeWidth = 0
        } else {
            button.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            button.strokeWidth = (2 * resources.displayMetrics.density).toInt()
            button.strokeColor = ColorStateList.valueOf(ctx.getColor(R.color.metro_accent))
        }
        button.setTextColor(ctx.getColor(R.color.metro_text))
    }

    private fun renderBondedHosts(hosts: List<HidBondedHost>) {
        bondedDevicesContainer.removeAllViews()
        if (hosts.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = getString(R.string.direct_ble_bonded_empty)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(resources.getColor(R.color.metro_text_secondary, null))
                setPadding(0, 8, 0, 8)
            }
            bondedDevicesContainer.addView(empty)
            return
        }
        val inflater = LayoutInflater.from(requireContext())
        for (host in hosts) {
            val row = inflater.inflate(R.layout.item_bonded_device, bondedDevicesContainer, false)
            row.findViewById<TextView>(R.id.deviceNameText).text = buildString {
                append(host.name)
                if (host.likelyHost) append("  · ПК")
                append('\n')
                append(host.address)
            }
            row.findViewById<MaterialButton>(R.id.connectHidButton).apply {
                val hidUp = viewModel.bleUiState.value is BleConnectionState.Connected
                text = getString(
                    if (hidUp) R.string.connect_hid_nudge else R.string.connect_hid,
                )
                setOnClickListener {
                    if (!viewModel.connectBleHost(host.address)) {
                        showMessage(getString(R.string.link_send_failed))
                    }
                }
            }
            row.findViewById<MaterialButton>(R.id.forgetButton).apply {
                val hidUp = viewModel.bleUiState.value is BleConnectionState.Connected
                isEnabled = !hidUp
                setOnClickListener {
                    if (viewModel.bleUiState.value is BleConnectionState.Connected) {
                        showMessage(getString(R.string.direct_ble_forget_blocked))
                        return@setOnClickListener
                    }
                    viewModel.forgetBleHost(host.address)
                }
            }
            bondedDevicesContainer.addView(row)
            val spacer = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (4 * resources.displayMetrics.density).toInt(),
                )
            }
            bondedDevicesContainer.addView(spacer)
        }
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

    private fun updateConnectionUi(
        state: EspLinkState,
        transport: LinkTransport,
        ble: BleConnectionState,
    ) {
        val wifiActive = transport == LinkTransport.Wifi
        val usbActive = transport == LinkTransport.Usb
        val bleActive = transport == LinkTransport.DirectBle

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

        directBleStatusText.text = when (ble) {
            is BleConnectionState.Connected ->
                getString(R.string.direct_ble_status_connected, ble.hostName)
            is BleConnectionState.Connecting ->
                getString(R.string.direct_ble_status_connecting, ble.hostName)
            BleConnectionState.Registering -> getString(R.string.direct_ble_status_registering)
            BleConnectionState.Registered -> getString(R.string.direct_ble_status_registered)
            is BleConnectionState.Error -> ble.message
            BleConnectionState.Disconnected -> getString(R.string.direct_ble_status_idle)
        }

        val connected = state is EspLinkState.Connected
        val busy = state == EspLinkState.Connecting || state == EspLinkState.RequestingPermission
        connectWifiButton.isEnabled = !(wifiActive && (connected || busy))
        disconnectWifiButton.isEnabled = wifiActive && (connected || busy)
        connectUsbButton.isEnabled = !(usbActive && (connected || busy))
        disconnectUsbButton.isEnabled = usbActive && (connected || busy)
        registerHidButton.isEnabled = !(bleActive && ble is BleConnectionState.Connected)
        connectHidPcButton.isEnabled = ble !is BleConnectionState.Connecting
        connectHidPcButton.text = getString(
            if (ble is BleConnectionState.Connected) {
                R.string.connect_hid_nudge
            } else {
                R.string.direct_ble_connect_pc
            },
        )
        openTouchpadButton.isEnabled = ble is BleConnectionState.Connected
        disconnectHidButton.isEnabled = bleActive && (
            ble is BleConnectionState.Connected ||
                ble is BleConnectionState.Connecting ||
                ble is BleConnectionState.Registered ||
                ble is BleConnectionState.Registering
            )
        if (::bondedDevicesContainer.isInitialized) {
            renderBondedHosts(viewModel.bleHosts.value)
        }
    }

    private fun copyLogToClipboard() {
        val text = debugLogText.text?.toString().orEmpty()
        if (text.isBlank() || text == "—") {
            showMessage(getString(R.string.log_empty))
            return
        }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("ArduHUD link log", text))
        showMessage(getString(R.string.log_copied))
    }

    private fun showMessage(message: String) {
        view?.let { Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show() }
    }

    fun prepareTutorialStep(targetId: Int) {
        if (targetId == R.id.connectionTypeHeader) {
            typeListExpanded = true
            applyTypePickerUi()
        }
        val scroll = view as? android.widget.ScrollView ?: return
        val target = view?.findViewById<View>(targetId) ?: return
        scroll.post {
            scroll.scrollTo(0, target.top)
        }
    }

    companion object {
        private const val STATE_SELECTED_TYPE = "connection_selected_type"
        private const val STATE_TYPE_LIST_EXPANDED = "connection_type_list_expanded"
    }
}
