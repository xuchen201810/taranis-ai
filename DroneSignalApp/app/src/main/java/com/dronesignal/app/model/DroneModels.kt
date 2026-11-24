package com.dronesignal.app.model

/** Parsed / aggregated drone telemetry received over the air. */
data class DroneTelemetry(
    val source: String = "UDP:14550",
    val sysId: Int = 0,
    val compId: Int = 0,
    val mavType: String = "",
    val autopilot: String = "",
    val baseMode: String = "",
    val customMode: Long = 0,
    val systemStatus: String = "",
    val batteryPercent: Int? = null,
    val voltageV: Float? = null,
    val currentA: Float? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val altM: Float? = null,
    val relativeAltM: Float? = null,
    val fixType: Int? = null,
    val satellites: Int? = null,
    val rollDeg: Float? = null,
    val pitchDeg: Float? = null,
    val yawDeg: Float? = null,
    val airspeedMs: Float? = null,
    val groundspeedMs: Float? = null,
    val headingDeg: Int? = null,
    val climbMs: Float? = null,
    val lastUpdate: Long = System.currentTimeMillis()
)

data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val levelDbm: Int,
    val frequencyMHz: Int,
    val capabilities: String,
    val isDrone: Boolean,
    val droneHint: String?,
    val connected: Boolean,
    val timestamp: Long
)

data class BleDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val isDrone: Boolean,
    val droneHint: String?,
    val timestamp: Long
)

data class LogEntry(
    val time: String,
    val level: String,
    val message: String
)
