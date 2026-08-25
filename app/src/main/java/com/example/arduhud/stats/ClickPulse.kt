package com.example.arduhud.stats

import com.example.arduhud.sensors.SensorChannel

/** One detect→click pulse with dominant signed axis of the motion burst. */
data class ClickPulse(
    val timestampNs: Long,
    val axis: SensorChannel?,
    val sign: Int,
    val peakAbs: Float,
) {
    val directionKey: String
        get() {
            val ax = axis ?: return "?"
            val prefix = if (sign < 0) "−" else "+"
            return prefix + ax.shortLabel
        }

    fun isOppositeOf(other: ClickPulse): Boolean {
        val a = axis ?: return false
        val b = other.axis ?: return false
        return a == b && sign != 0 && other.sign != 0 && sign == -other.sign
    }
}
