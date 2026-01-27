package com.example.raceboxtelemetry.ble

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.raceboxtelemetry.MainActivity
import com.example.raceboxtelemetry.R
import com.example.raceboxtelemetry.api.TelemetryApi
import com.example.raceboxtelemetry.model.TelemetryData
import com.example.raceboxtelemetry.preferences.DataFieldPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.min

class RaceBoxService : Service() {

    companion object {
        private const val TAG = "RaceBoxService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "racebox_service_channel"
        const val ACTION_START = "com.example.raceboxtelemetry.START"
        const val ACTION_STOP = "com.example.raceboxtelemetry.STOP"
    }

    private val binder = LocalBinder()
    private lateinit var raceBoxManager: RaceBoxManager
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var prefs: DataFieldPreferences

    private var dataSendingJob: Job? = null
    private var consecutiveFailures = 0
    private var isSending = false
    private val MAX_CONSECUTIVE_FAILURES = 10
    private var lastSentTime = 0L
    private var configSyncJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): RaceBoxService = this@RaceBoxService
    }

    override fun onCreate() {
        super.onCreate()
        prefs = DataFieldPreferences(this)
        raceBoxManager = RaceBoxManager(this)
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification("Starting..."))
                startDataCollection()
            }
            ACTION_STOP -> {
                stopDataCollection()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    fun getRaceBoxManager(): RaceBoxManager = raceBoxManager

    private fun startDataCollection() {
        Log.d(TAG, "Starting data collection at ${prefs.getFrequencyHz()} Hz (${prefs.sendIntervalMs}ms interval)")

        // Start periodic config sync from server
        startConfigSync()

        dataSendingJob = serviceScope.launch {
            raceBoxManager.telemetryData.collect { data ->
                val now = System.currentTimeMillis()
                val timeSinceLastSend = now - lastSentTime

                // Respect send interval preference
                if (timeSinceLastSend >= prefs.sendIntervalMs) {
                    Log.d(TAG, "Received telemetry data: Speed=${data.speed}")
                    sendTelemetryData(data)
                    lastSentTime = now
                } else {
                    Log.d(TAG, "Skipping send (too soon: ${timeSinceLastSend}ms < ${prefs.sendIntervalMs}ms)")
                }
            }
        }

        // Update notification when connection state changes
        serviceScope.launch {
            raceBoxManager.connectionState.collect { state ->
                updateNotification(state)
            }
        }

        // Monitor network health and update notification
        serviceScope.launch {
            while (isActive) {
                delay(5000) // Check every 5 seconds
                if (consecutiveFailures >= 5) {
                    // Update notification to show network issues
                    updateNotification(raceBoxManager.connectionState.value)
                }
            }
        }
    }

    private fun updateNotification(state: RaceBoxManager.ConnectionState) {
        val baseMessage = when (state) {
            RaceBoxManager.ConnectionState.CONNECTED -> "Connected - Streaming data"
            RaceBoxManager.ConnectionState.CONNECTING -> "Connecting..."
            RaceBoxManager.ConnectionState.SCANNING -> "Scanning for devices..."
            RaceBoxManager.ConnectionState.DISCONNECTED -> "Disconnected"
            RaceBoxManager.ConnectionState.ERROR -> "Connection error"
        }

        val message = if (consecutiveFailures >= 5) {
            "$baseMessage (Network issues)"
        } else {
            baseMessage
        }

        val notification = createNotification(message)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startConfigSync() {
        configSyncJob = serviceScope.launch {
            // Sync immediately on start
            syncConfigFromServer()

            // Then sync every 30 seconds
            while (isActive) {
                delay(30000)
                syncConfigFromServer()
            }
        }
    }

    private suspend fun syncConfigFromServer() {
        try {
            val result = TelemetryApi.getConfig()
            result.onSuccess { config ->
                val serverFrequencyHz = config.send_frequency_hz
                val serverDelayMs = config.video_delay_ms

                if (serverFrequencyHz > 0) {
                    val serverIntervalMs = (1000L / serverFrequencyHz)
                    if (prefs.sendIntervalMs != serverIntervalMs) {
                        prefs.sendIntervalMs = serverIntervalMs
                        Log.d(TAG, "✓ Config synced from server: ${serverFrequencyHz}Hz (${serverIntervalMs}ms)")
                    }
                }

                if (serverDelayMs >= 0 && prefs.videoDelayMs != serverDelayMs) {
                    prefs.videoDelayMs = serverDelayMs
                    Log.d(TAG, "✓ Video delay synced from server: ${serverDelayMs}ms")
                }
            }
            result.onFailure { e ->
                Log.w(TAG, "Failed to sync config from server: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing config", e)
        }
    }

    private fun stopDataCollection() {
        dataSendingJob?.cancel()
        configSyncJob?.cancel()
        raceBoxManager.disconnect()
        Log.d(TAG, "Data collection stopped")
    }

    private suspend fun sendTelemetryData(data: com.example.raceboxtelemetry.model.RaceBoxData) {
        // Skip if already sending (prevent queue buildup)
        if (isSending) {
            Log.d(TAG, "Previous send still in progress, skipping")
            return
        }

        isSending = true
        try {
            // Only include fields that are enabled in preferences
            val telemetry = TelemetryData(
                speed = if (prefs.sendSpeed) data.speed else null,
                gLat = if (prefs.sendGLat) data.gLat else null,
                gLong = if (prefs.sendGLong) data.gLong else null,
                latitude = if (prefs.sendLatitude) data.latitude else null,
                longitude = if (prefs.sendLongitude) data.longitude else null,
                satellites = if (prefs.sendSatellites) data.satellites else null,
                timestamp = if (prefs.sendTimestamp) data.timestamp else null
            )

            Log.d(TAG, "Sending telemetry: $telemetry")

            // Retry logic with exponential backoff
            var retryCount = 0
            val maxRetries = 2
            var success = false

            while (retryCount <= maxRetries && !success) {
                val result = TelemetryApi.sendTelemetry(telemetry)

                result.onSuccess {
                    consecutiveFailures = 0
                    success = true
                    if (retryCount > 0) {
                        Log.d(TAG, "✓ Data sent successfully after $retryCount retries")
                    } else {
                        Log.d(TAG, "✓ Data sent successfully")
                    }
                }

                result.onFailure { e ->
                    retryCount++
                    consecutiveFailures++

                    if (retryCount <= maxRetries) {
                        val delayMs = min(1000L * retryCount, 2000L)
                        Log.w(TAG, "⚠ Send failed (attempt $retryCount/$maxRetries), retrying in ${delayMs}ms: ${e.message}")
                        delay(delayMs)
                    } else {
                        Log.e(TAG, "✗ Failed to send data after $maxRetries retries: ${e.message}")

                        // If too many consecutive failures, warn user
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            Log.e(TAG, "⚠⚠⚠ Network appears to be down - $consecutiveFailures consecutive failures")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            consecutiveFailures++
            Log.e(TAG, "✗ Error sending telemetry", e)
        } finally {
            isSending = false
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "RaceBox Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "RaceBox telemetry streaming service"
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RaceBox Telemetry")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDataCollection()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }
}
