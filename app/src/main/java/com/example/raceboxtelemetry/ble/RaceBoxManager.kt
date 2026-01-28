package com.example.raceboxtelemetry.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.example.raceboxtelemetry.model.RaceBoxData
import com.example.raceboxtelemetry.preferences.DataFieldPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.*

@SuppressLint("MissingPermission")
class RaceBoxManager(private val context: Context) {

    companion object {
        private const val TAG = "RaceBoxManager"

        // RaceBox BLE UUIDs (Nordic UART Service)
        private val UART_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        private val UART_RX_CHAR_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        private val UART_TX_CHAR_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothAdapter: BluetoothAdapter? =
        BluetoothManager::class.java.cast(context.getSystemService(Context.BLUETOOTH_SERVICE))
            ?.adapter

    private val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    private var bluetoothGatt: BluetoothGatt? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _telemetryData = MutableStateFlow(RaceBoxData())
    val telemetryData: StateFlow<RaceBoxData> = _telemetryData

    private val _scanResults = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scanResults: StateFlow<List<BluetoothDevice>> = _scanResults

    // G-force zero offsets for calibration
    private val prefs = DataFieldPreferences(context)
    private var gLatZeroOffset: Double = 0.0
    private var gLongZeroOffset: Double = 0.0
    private var gZZeroOffset: Double = 0.0

    // Tilt compensation angles (in radians)
    private var tiltRoll: Double = 0.0  // Rotation around longitudinal axis
    private var tiltPitch: Double = 0.0 // Rotation around lateral axis

    // Store last raw sensor readings (before any correction) for zeroing
    private var lastRawGLat: Double = 0.0
    private var lastRawGLong: Double = 0.0
    private var lastRawGZ: Double = 0.0  // Vertical axis

    // Device persistence for remembering connected devices and aliases
    private val devicePersistence = DevicePersistence(context)
    private var connectedDevice: BluetoothDevice? = null

    init {
        // Load saved zero offsets and tilt angles
        gLatZeroOffset = prefs.gLatZero.toDouble()
        gLongZeroOffset = prefs.gLongZero.toDouble()
        tiltRoll = prefs.tiltRoll.toDouble()
        tiltPitch = prefs.tiltPitch.toDouble()
    }

    enum class ConnectionState {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.name?.contains("RaceBox", ignoreCase = true) == true) {
                val currentList = _scanResults.value.toMutableList()
                if (!currentList.contains(device)) {
                    currentList.add(device)
                    _scanResults.value = currentList
                }
                Log.d(TAG, "Found RaceBox device: ${device.name} (${device.address})")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            _connectionState.value = ConnectionState.ERROR
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server")
                    _connectionState.value = ConnectionState.CONNECTING
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered")
                val service = gatt.getService(UART_SERVICE_UUID)
                if (service != null) {
                    val txCharacteristic = service.getCharacteristic(UART_TX_CHAR_UUID)
                    if (txCharacteristic != null) {
                        // Enable notifications
                        gatt.setCharacteristicNotification(txCharacteristic, true)
                        val descriptor = txCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                        descriptor?.let {
                            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(it)
                        }
                        _connectionState.value = ConnectionState.CONNECTED
                        Log.d(TAG, "Notifications enabled")

                        // Save the connected device
                        connectedDevice?.let { device ->
                            devicePersistence.saveLastConnectedDevice(device.address, device.name ?: "Unknown")
                        }
                    }
                } else {
                    Log.e(TAG, "UART service not found")
                    _connectionState.value = ConnectionState.ERROR
                }
            } else {
                Log.e(TAG, "Service discovery failed with status: $status")
                _connectionState.value = ConnectionState.ERROR
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == UART_TX_CHAR_UUID) {
                parseRaceBoxData(value)
            }
        }
    }

    fun startScan() {
        if (bluetoothLeScanner == null) {
            Log.e(TAG, "Bluetooth LE Scanner not available")
            return
        }

        _connectionState.value = ConnectionState.SCANNING
        _scanResults.value = emptyList()

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(UART_SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
        Log.d(TAG, "Started BLE scan")
    }

    fun stopScan() {
        bluetoothLeScanner?.stopScan(scanCallback)
        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
        Log.d(TAG, "Stopped BLE scan")
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        connectedDevice = device
        _connectionState.value = ConnectionState.CONNECTING
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
        Log.d(TAG, "Connecting to ${device.name} (${device.address})")
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectedDevice = null
        _connectionState.value = ConnectionState.DISCONNECTED
        Log.d(TAG, "Disconnected")
    }

    private fun parseRaceBoxData(data: ByteArray) {
        try {
            // RaceBox sends ASCII data in specific formats
            // Common format: $GPRMC or $GPGGA NMEA sentences, or custom binary format
            val dataString = String(data, Charsets.UTF_8)
            Log.d(TAG, "Received data: $dataString")

            // Check if it's NMEA format
            if (dataString.startsWith("$")) {
                parseNMEA(dataString)
            } else {
                // Try parsing as binary format (if RaceBox sends binary telemetry)
                parseBinaryData(data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing data", e)
        }
    }

    private fun parseNMEA(nmea: String) {
        val parts = nmea.split(",")
        when {
            nmea.startsWith("\$GPRMC") || nmea.startsWith("\$GNRMC") -> {
                // RMC: Recommended Minimum Navigation Information
                // Format: $GPRMC,hhmmss.ss,A,ddmm.mm,N,dddmm.mm,E,speed,course,ddmmyy,,,A*hh
                if (parts.size >= 8) {
                    val speedKnots = parts[7].toDoubleOrNull() ?: 0.0
                    val speedKmh = speedKnots * 1.852  // Convert knots to km/h

                    val currentData = _telemetryData.value
                    _telemetryData.value = currentData.copy(
                        speed = speedKmh,
                        timestamp = System.currentTimeMillis()
                    )
                }
            }
            nmea.startsWith("\$GPGGA") || nmea.startsWith("\$GNGGA") -> {
                // GGA: Global Positioning System Fix Data
                // Contains position and quality information
                if (parts.size >= 10) {
                    val lat = parseCoordinate(parts[2], parts[3])
                    val lon = parseCoordinate(parts[4], parts[5])
                    val satellites = parts[7].toIntOrNull() ?: 0
                    val altitude = parts[9].toDoubleOrNull() ?: 0.0

                    val currentData = _telemetryData.value
                    _telemetryData.value = currentData.copy(
                        latitude = lat,
                        longitude = lon,
                        satellites = satellites,
                        altitude = altitude,
                        timestamp = System.currentTimeMillis()
                    )
                }
            }
        }
    }

    private fun parseBinaryData(data: ByteArray) {
        // RaceBox sends UBX binary format packets
        // Format: Header (0xB5 0x62) + Class/ID (0xFF 0x01) + Length + 80-byte Payload + Checksum

        // Log raw data for debugging
        val hexString = data.joinToString(" ") { "%02X".format(it) }
        Log.d(TAG, "Binary data (${data.size} bytes): $hexString")

        // Minimum packet size: 2 (header) + 2 (class/id) + 2 (length) + 80 (payload) + 2 (checksum) = 88 bytes
        if (data.size < 88) {
            Log.w(TAG, "Packet too small: ${data.size} bytes, expected at least 88")
            return
        }

        // Verify UBX header
        if (data[0] != 0xB5.toByte() || data[1] != 0x62.toByte()) {
            Log.w(TAG, "Invalid UBX header: ${data[0]} ${data[1]}")
            return
        }

        // Verify message class and ID (0xFF 0x01 = RaceBox Data Message)
        if (data[2] != 0xFF.toByte() || data[3] != 0x01.toByte()) {
            Log.d(TAG, "Not a RaceBox data message: class=${data[2]} id=${data[3]}")
            return
        }

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Parse payload (starts at offset 6)
        val payloadOffset = 6

        // Speed: offset 48 (from payload start) + 6 (header) = 54, Int32, in mm/s
        val speedMmPerSec = buffer.getInt(payloadOffset + 48)
        val speedKmh = speedMmPerSec / 1000.0 / 1000.0 * 3600.0 // Convert mm/s to km/h

        // G-Forces: offset 68, 70, 72, Int16, in milli-g
        val gForceX = buffer.getShort(payloadOffset + 68).toInt() // Forward/back
        val gForceY = buffer.getShort(payloadOffset + 70).toInt() // Lateral (left/right)
        val gForceZ = buffer.getShort(payloadOffset + 72).toInt() // Up/down

        val gLat = gForceY / 1000.0  // Convert milli-g to g
        val gLong = gForceX / 1000.0 // Convert milli-g to g
        val gZ = gForceZ / 1000.0    // Convert milli-g to g

        // Store raw sensor readings for zeroing
        lastRawGLat = gLat
        lastRawGLong = gLong
        lastRawGZ = gZ

        // GPS coordinates: Int32 with factor of 10^7
        val lonRaw = buffer.getInt(payloadOffset + 24)
        val latRaw = buffer.getInt(payloadOffset + 28)
        val longitude = lonRaw / 10000000.0
        val latitude = latRaw / 10000000.0

        // Satellites
        val satellites = data[payloadOffset + 23].toInt() and 0xFF

        // Battery Status (offset 67 from payload start = offset 73 in full packet)
        val batteryByte = data[payloadOffset + 67].toInt() and 0xFF
        val batteryLevel = batteryByte and 0x7F  // Lower 7 bits = battery percentage
        val isCharging = (batteryByte and 0x80) != 0  // Bit 7 = charging status
        val inputVoltage = batteryByte / 10.0  // For Micro: voltage in volts

        // Validate values
        if (speedKmh < 0 || speedKmh > 500) {
            Log.w(TAG, "Invalid speed: $speedKmh km/h, skipping update")
            return
        }

        if (gLat < -5.0 || gLat > 5.0) {
            Log.w(TAG, "Invalid gLat: $gLat g, skipping update")
            return
        }

        if (gLong < -5.0 || gLong > 5.0) {
            Log.w(TAG, "Invalid gLong: $gLong g, skipping update")
            return
        }

        // Apply tilt compensation and zero offsets to G-force readings
        // Strategy: rotate raw readings to level frame first, then subtract offsets

        // Apply rotation to transform to level reference frame
        val (gLatRotated, gLongRotated) = applyTiltCompensation(gLat, gLong, gZ)

        // Now subtract offsets (which were also calculated in level frame)
        val gLatCorrected = gLatRotated - gLatZeroOffset
        val gLongCorrected = gLongRotated - gLongZeroOffset

        // Debug logging for tilt compensation
        if (tiltRoll != 0.0 || tiltPitch != 0.0) {
            Log.d(TAG, "Tilt compensation: raw(${"%3f".format(gLat)}, ${"%.3f".format(gLong)}, ${"%.3f".format(gZ)}) → rotated(${"%.3f".format(gLatRotated)}, ${"%.3f".format(gLongRotated)}) → final(${"%.3f".format(gLatCorrected)}, ${"%.3f".format(gLongCorrected)})")
            Log.d(TAG, "Tilt angles: roll=${"%.1f".format(Math.toDegrees(tiltRoll))}°, pitch=${"%.1f".format(Math.toDegrees(tiltPitch))}°")
        }

        val currentData = _telemetryData.value
        _telemetryData.value = currentData.copy(
            speed = speedKmh,
            gLat = gLatCorrected,
            gLong = gLongCorrected,
            latitude = latitude,
            longitude = longitude,
            satellites = satellites,
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            inputVoltage = inputVoltage,
            timestamp = System.currentTimeMillis()
        )

        Log.d(TAG, "Parsed UBX: Speed=${"%.1f".format(speedKmh)} km/h, G-Lat=${"%.2f".format(gLat)}→${"%.2f".format(gLatCorrected)}, G-Long=${"%.2f".format(gLong)}→${"%.2f".format(gLongCorrected)}, Lat=${"%.6f".format(latitude)}, Lon=${"%.6f".format(longitude)}, Sats=$satellites")
    }

    private fun parseCoordinate(value: String, direction: String): Double {
        if (value.isEmpty()) return 0.0
        try {
            val degrees = value.substring(0, value.indexOf(".") - 2).toInt()
            val minutes = value.substring(value.indexOf(".") - 2).toDouble()
            var coordinate = degrees + minutes / 60.0
            if (direction == "S" || direction == "W") {
                coordinate = -coordinate
            }
            return coordinate
        } catch (e: Exception) {
            return 0.0
        }
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /**
     * Apply tilt compensation to g-force readings using simple scaling.
     *
     * When tilted, the sensor's sensitivity is reduced by cos(tilt_angle) for each axis.
     * We scale by 1/cos(angle) to restore full sensitivity.
     *
     * @param gLat Lateral g-force (raw sensor reading)
     * @param gLong Longitudinal g-force (raw sensor reading)
     * @param gZ Vertical g-force (raw sensor reading)
     * @return Pair of (compensated gLat, compensated gLong)
     */
    private fun applyTiltCompensation(gLat: Double, gLong: Double, gZ: Double): Pair<Double, Double> {
        // If no tilt compensation is active, return original values
        if (tiltRoll == 0.0 && tiltPitch == 0.0) {
            return Pair(gLat, gLong)
        }

        // Calculate scaling factors based on tilt angles
        // When tilted by angle θ, sensitivity is reduced by cos(θ)
        // So we multiply by 1/cos(θ) to restore it
        val rollScaleFactor = if (abs(tiltRoll) < PI / 2) {
            1.0 / cos(tiltRoll)
        } else {
            1.0 // Don't compensate for extreme tilts
        }

        val pitchScaleFactor = if (abs(tiltPitch) < PI / 2) {
            1.0 / cos(tiltPitch)
        } else {
            1.0 // Don't compensate for extreme tilts
        }

        // Apply scaling to restore sensitivity
        val gLatCompensated = gLat * rollScaleFactor
        val gLongCompensated = gLong * pitchScaleFactor

        return Pair(gLatCompensated, gLongCompensated)
    }

    /**
     * Zero the G-meter by setting current G-force readings as the zero point.
     * This calibrates for when the device is mounted at an angle with tilt compensation.
     *
     * Uses the current gravity vector to calculate tilt angles and applies rotation
     * matrix compensation to maintain consistent sensitivity regardless of mounting angle.
     */
    fun zeroGMeter() {
        // Use the last raw sensor readings (before any correction was applied)
        val rawGLat = lastRawGLat
        val rawGLong = lastRawGLong
        val rawGZ = lastRawGZ

        // Calculate tilt angles from gravity vector
        // Roll: rotation around longitudinal (forward) axis - tilting left/right
        // Pitch: rotation around lateral (side) axis - tilting forward/back
        tiltRoll = atan2(rawGLat, rawGZ)
        tiltPitch = atan2(rawGLong, rawGZ)

        // Rotate the current readings to level frame to get the offsets
        // This way, when we apply the same rotation to future readings and subtract
        // these offsets, stationary readings at this angle will become (0, 0)
        val (gLatLevel, gLongLevel) = applyTiltCompensation(rawGLat, rawGLong, rawGZ)

        // Store the level-frame readings as offsets
        gLatZeroOffset = gLatLevel
        gLongZeroOffset = gLongLevel

        // Save to preferences
        prefs.gLatZero = gLatZeroOffset.toFloat()
        prefs.gLongZero = gLongZeroOffset.toFloat()
        prefs.tiltRoll = tiltRoll.toFloat()
        prefs.tiltPitch = tiltPitch.toFloat()

        Log.d(TAG, "G-meter zeroed with tilt compensation:")
        Log.d(TAG, "  Raw readings: gLat=${"%.3f".format(rawGLat)}, gLong=${"%.3f".format(rawGLong)}, gZ=${"%.3f".format(rawGZ)}")
        Log.d(TAG, "  Level frame: gLat=${"%.3f".format(gLatLevel)}, gLong=${"%.3f".format(gLongLevel)}")
        Log.d(TAG, "  Tilt angles: roll=${"%.1f".format(Math.toDegrees(tiltRoll))}°, pitch=${"%.1f".format(Math.toDegrees(tiltPitch))}°")
    }

    /**
     * Reset G-meter zero offsets and tilt compensation to default
     */
    fun resetGZero() {
        gLatZeroOffset = 0.0
        gLongZeroOffset = 0.0
        tiltRoll = 0.0
        tiltPitch = 0.0
        prefs.resetGZero()
        Log.d(TAG, "G-meter zero and tilt compensation reset")
    }

    /**
     * Reconnect to the last connected device
     */
    fun reconnectToLastDevice(): Boolean {
        val lastDevice = devicePersistence.getLastConnectedDevice()
        if (lastDevice != null) {
            val (address, name) = lastDevice
            try {
                val device = bluetoothAdapter?.getRemoteDevice(address)
                if (device != null) {
                    connect(device)
                    Log.d(TAG, "Reconnecting to last device: $name ($address)")
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reconnect to last device", e)
            }
        }
        return false
    }

    /**
     * Get the last connected device info
     */
    fun getLastConnectedDevice(): Pair<String?, String?>? {
        return devicePersistence.getLastConnectedDevice()
    }

    /**
     * Check if there is a last connected device available
     */
    fun hasLastConnectedDevice(): Boolean {
        return devicePersistence.getLastConnectedDevice() != null
    }

    /**
     * Save an alias for a device
     */
    fun saveDeviceAlias(address: String, alias: String) {
        devicePersistence.saveDeviceAlias(address, alias)
    }

    /**
     * Get alias for a device
     */
    fun getDeviceAlias(address: String): String? {
        return devicePersistence.getDeviceAlias(address)
    }

    /**
     * Get all device aliases
     */
    fun getAllDeviceAliases(): Map<String, String> {
        return devicePersistence.getDeviceAliases()
    }

    /**
     * Remove alias for a device
     */
    fun removeDeviceAlias(address: String) {
        devicePersistence.removeDeviceAlias(address)
    }
}
