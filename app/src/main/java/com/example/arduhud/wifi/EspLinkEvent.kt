package com.example.arduhud.wifi

sealed class EspLinkEvent {
    data class PenMode(val enabled: Boolean) : EspLinkEvent()
    data class Pos(val x: Int, val y: Int) : EspLinkEvent()
}
