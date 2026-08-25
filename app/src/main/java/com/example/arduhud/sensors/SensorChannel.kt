package com.example.arduhud.sensors

enum class SensorChannel(
    val shortLabel: String,
    val isAccel: Boolean,
    val colorHex: String,
) {
    ACC_X("A.X", true, "#4FC3F7"),
    ACC_Y("A.Y", true, "#29B6F6"),
    ACC_Z("A.Z", true, "#0288D1"),
    ACC_MAG("A.||", true, "#81D4FA"),
    GYRO_X("G.X", false, "#AED581"),
    GYRO_Y("G.Y", false, "#8BC34A"),
    GYRO_Z("G.Z", false, "#689F38"),
    GYRO_MAG("G.||", false, "#C5E1A5");

    companion object {
        val accelOptions: List<SensorChannel> = entries.filter { it.isAccel }
        val gyroOptions: List<SensorChannel> = entries.filter { !it.isAccel }
    }
}

enum class TimeWindowSec(val seconds: Int) {
    S2(2),
    S5(5),
    S10(10),
    S20(20),
    S40(40),
    S60(60),
    S120(120),
}
