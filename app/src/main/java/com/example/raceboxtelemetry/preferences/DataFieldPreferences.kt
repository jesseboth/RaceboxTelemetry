package com.example.raceboxtelemetry.preferences

import android.content.Context
import android.content.SharedPreferences

class DataFieldPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "data_field_preferences"

        // Preference keys
        private const val KEY_SPEED = "send_speed"
        private const val KEY_G_LAT = "send_g_lat"
        private const val KEY_G_LONG = "send_g_long"
        private const val KEY_LATITUDE = "send_latitude"
        private const val KEY_LONGITUDE = "send_longitude"
        private const val KEY_SATELLITES = "send_satellites"
        private const val KEY_TIMESTAMP = "send_timestamp"
        private const val KEY_SEND_INTERVAL = "send_interval_ms"
        private const val KEY_API_URL = "api_url"
        private const val KEY_VIDEO_DELAY = "video_delay_ms"
        private const val KEY_G_LAT_ZERO = "g_lat_zero"
        private const val KEY_G_LONG_ZERO = "g_long_zero"

        // Default values (speed and g-meter enabled by default)
        private const val DEFAULT_SPEED = true
        private const val DEFAULT_G_LAT = true
        private const val DEFAULT_G_LONG = true
        private const val DEFAULT_LATITUDE = false
        private const val DEFAULT_LONGITUDE = false
        private const val DEFAULT_SATELLITES = false
        private const val DEFAULT_TIMESTAMP = true
        private const val DEFAULT_SEND_INTERVAL = 100L // 10Hz (100ms)
        private const val DEFAULT_API_URL = "http://192.168.1.100:5000"
        private const val DEFAULT_VIDEO_DELAY = 1500 // 1.5 seconds
    }

    // Speed
    var sendSpeed: Boolean
        get() = prefs.getBoolean(KEY_SPEED, DEFAULT_SPEED)
        set(value) = prefs.edit().putBoolean(KEY_SPEED, value).apply()

    // G-Lateral
    var sendGLat: Boolean
        get() = prefs.getBoolean(KEY_G_LAT, DEFAULT_G_LAT)
        set(value) = prefs.edit().putBoolean(KEY_G_LAT, value).apply()

    // G-Longitudinal
    var sendGLong: Boolean
        get() = prefs.getBoolean(KEY_G_LONG, DEFAULT_G_LONG)
        set(value) = prefs.edit().putBoolean(KEY_G_LONG, value).apply()

    // Latitude
    var sendLatitude: Boolean
        get() = prefs.getBoolean(KEY_LATITUDE, DEFAULT_LATITUDE)
        set(value) = prefs.edit().putBoolean(KEY_LATITUDE, value).apply()

    // Longitude
    var sendLongitude: Boolean
        get() = prefs.getBoolean(KEY_LONGITUDE, DEFAULT_LONGITUDE)
        set(value) = prefs.edit().putBoolean(KEY_LONGITUDE, value).apply()

    // Satellites
    var sendSatellites: Boolean
        get() = prefs.getBoolean(KEY_SATELLITES, DEFAULT_SATELLITES)
        set(value) = prefs.edit().putBoolean(KEY_SATELLITES, value).apply()

    // Timestamp
    var sendTimestamp: Boolean
        get() = prefs.getBoolean(KEY_TIMESTAMP, DEFAULT_TIMESTAMP)
        set(value) = prefs.edit().putBoolean(KEY_TIMESTAMP, value).apply()

    // Send interval in milliseconds
    var sendIntervalMs: Long
        get() = prefs.getLong(KEY_SEND_INTERVAL, DEFAULT_SEND_INTERVAL)
        set(value) = prefs.edit().putLong(KEY_SEND_INTERVAL, value).apply()

    // API URL
    var apiUrl: String
        get() = prefs.getString(KEY_API_URL, DEFAULT_API_URL) ?: DEFAULT_API_URL
        set(value) = prefs.edit().putString(KEY_API_URL, value).apply()

    // Video delay in milliseconds
    var videoDelayMs: Int
        get() = prefs.getInt(KEY_VIDEO_DELAY, DEFAULT_VIDEO_DELAY)
        set(value) = prefs.edit().putInt(KEY_VIDEO_DELAY, value).apply()

    // G-force zero offsets for calibration
    var gLatZero: Float
        get() = prefs.getFloat(KEY_G_LAT_ZERO, 0.0f)
        set(value) = prefs.edit().putFloat(KEY_G_LAT_ZERO, value).apply()

    var gLongZero: Float
        get() = prefs.getFloat(KEY_G_LONG_ZERO, 0.0f)
        set(value) = prefs.edit().putFloat(KEY_G_LONG_ZERO, value).apply()

    // Reset G-force zero offsets
    fun resetGZero() {
        prefs.edit()
            .putFloat(KEY_G_LAT_ZERO, 0.0f)
            .putFloat(KEY_G_LONG_ZERO, 0.0f)
            .apply()
    }

    // Helper to check if at least one field is enabled
    fun hasEnabledFields(): Boolean {
        return sendSpeed || sendGLat || sendGLong || sendLatitude ||
               sendLongitude || sendSatellites || sendTimestamp
    }

    // Helper to get frequency in Hz
    fun getFrequencyHz(): Double {
        return 1000.0 / sendIntervalMs
    }
}
