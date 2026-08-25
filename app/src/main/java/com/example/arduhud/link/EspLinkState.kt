package com.example.arduhud.link

sealed interface EspLinkState {
    data object Disconnected : EspLinkState
    data object RequestingPermission : EspLinkState
    data object Connecting : EspLinkState
    data class Connected(val endpoint: String) : EspLinkState
    data class Error(val message: String) : EspLinkState
}
