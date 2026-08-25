package com.example.arduhud.ble

sealed class BleConnectionState {
    data object Disconnected : BleConnectionState()
    data object Registering : BleConnectionState()
    data object Registered : BleConnectionState()
    data class Connecting(val hostName: String) : BleConnectionState()
    data class Connected(val hostName: String) : BleConnectionState()
    data class Error(val message: String) : BleConnectionState()
}

enum class MouseButton(val mask: Byte) {
    LEFT(0x01),
    RIGHT(0x02),
    MIDDLE(0x04),
    BUTTON4(0x08),
    BUTTON5(0x10),
}
