package com.example.arduhud.sensors

data class MotionData(
    val timestampNs: Long = 0L,
    val linearAccelX: Float = 0f,
    val linearAccelY: Float = 0f,
    val linearAccelZ: Float = 0f,
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,
    val linearMagnitude: Float = 0f,
    val gyroMagnitude: Float = 0f,
    val channelValues: Map<SensorChannel, Float> = emptyMap(),
    val activity: Float = 0f,
    val isMoving: Boolean = false,
    val useSum: Boolean = true,
    val enabledChannels: Set<SensorChannel> = emptySet(),
    val clickMarkerNs: Long? = null,
) {
    fun valueOf(channel: SensorChannel): Float = channelValues[channel] ?: 0f
}
