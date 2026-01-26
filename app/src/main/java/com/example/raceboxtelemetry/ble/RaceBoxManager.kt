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

    init {
        // Load saved zero offsets
        gLatZeroOffset = prefs.gLatZero.toDouble()
        gLongZeroOffset = prefs.gLongZero.toDouble()
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
        _connectionState.value = ConnectionState.CONNECTING
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
        Log.d(TAG, "Connecting to ${device.name} (${device.address})")
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
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

        // GPS coordinates: Int32 with factor of 10^7
        val lonRaw = buffer.getInt(payloadOffset + 24)
        val latRaw = buffer.getInt(payloadOffset + 28)
        val longitude = lonRaw / 10000000.0
        val latitude = latRaw / 10000000.0

        // Satellites
        val satellites = data[payloadOffset + 23].toInt() and 0xFF

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

        // Apply zero offsets to G-force readings
        val gLatCorrected = gLat - gLatZeroOffset
        val gLongCorrected = gLong - gLongZeroOffset

        val currentData = _telemetryData.value
        _telemetryData.value = currentData.copy(
            speed = speedKmh,
            gLat = gLatCorrected,
            gLong = gLongCorrected,
            latitude = latitude,
            longitude = longitude,
            satellites = satellites,
            timestamp = System.currentTimeMillis()
        )

        Log.d(TAG, "Parsed UBX: Speed=${"%.1f".format(speedKmh)} km/h, G-Lat=${"%.2f".format(gLat)}, G-Long=${"%.2f".format(gLong)}, Lat=${"%.6f".format(latitude)}, Lon=${"%.6f".format(longitude)}, Sats=$satellites")
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
     * Zero the G-meter by setting current G-force readings as the zero point.
     * This calibrates for when the device is mounted at an angle.
     */
    fun zeroGMeter() {
        val currentData = _telemetryData.value
        gLatZeroOffset = currentData.gLat + gLatZeroOffset
        gLongZeroOffset = currentData.gLong + gLongZeroOffset

        // Save to preferences
        prefs.gLatZero = gLatZeroOffset.toFloat()
        prefs.gLongZero = gLongZeroOffset.toFloat()

        Log.d(TAG, "G-meter zeroed: gLatOffset=${"%.3f".format(gLatZeroOffset)}, gLongOffset=${"%.3f".format(gLongZeroOffset)}")
    }

    /**
     * Reset G-meter zero offsets to default (no offset)
     */
    fun resetGZero() {
        gLatZeroOffset = 0.0
        gLongZeroOffset = 0.0
        prefs.resetGZero()
        Log.d(TAG, "G-meter zero reset")
    }
}
