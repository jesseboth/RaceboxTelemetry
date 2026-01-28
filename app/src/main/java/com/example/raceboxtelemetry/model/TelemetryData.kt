package com.example.raceboxtelemetry.model

import com.google.gson.annotations.SerializedName

data class TelemetryData(
    @SerializedName("speed")
    val speed: Double? = null,  // Speed in km/h or mph

    @SerializedName("g_lat")
    val gLat: Double? = null,   // Lateral g-force

    @SerializedName("g_long")
    val gLong: Double? = null,  // Longitudinal g-force

    @SerializedName("timestamp")
    val timestamp: Long? = null,

    @SerializedName("latitude")
    val latitude: Double? = null,

    @SerializedName("longitude")
    val longitude: Double? = null,

    @SerializedName("satellites")
    val satellites: Int? = null
)

data class RaceBoxData(
    val speed: Double = 0.0,
    val gLat: Double = 0.0,
    val gLong: Double = 0.0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val satellites: Int = 0,
    val heading: Double = 0.0,
    val batteryLevel: Int = 0,       // Battery percentage (0-100) for Mini/Mini S, or 0 if not available
    val isCharging: Boolean = false,  // True if charging (Mini/Mini S only)
    val inputVoltage: Double = 0.0,   // Input voltage for Micro (in volts), or 0 if not applicable
    val timestamp: Long = System.currentTimeMillis()
)
