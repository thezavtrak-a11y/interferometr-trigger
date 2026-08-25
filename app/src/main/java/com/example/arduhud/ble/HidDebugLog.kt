package com.example.arduhud.ble

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HidDebugLog {
    private const val TAG = "BleHidManager"
    private const val MAX_LINES = 80

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun i(message: String) {
        Log.i(TAG, message)
        append(message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
        append("WARN: $message")
    }

    fun e(message: String) {
        Log.e(TAG, message)
        append("ERR: $message")
    }

    fun clear() {
        _lines.value = emptyList()
    }

    private fun append(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val next = (_lines.value + "[$time] $message").takeLast(MAX_LINES)
        _lines.value = next
    }
}
