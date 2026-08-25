package com.example.arduhud.usb

sealed interface UsbLinkState {
    data object Disconnected : UsbLinkState
    data object RequestingPermission : UsbLinkState
    data object Connecting : UsbLinkState
    data class Connected(val deviceName: String) : UsbLinkState
    data class Error(val message: String) : UsbLinkState
}
