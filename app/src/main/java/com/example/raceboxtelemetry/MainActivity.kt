package com.example.raceboxtelemetry

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.slider.Slider
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.raceboxtelemetry.ble.RaceBoxManager
import com.example.raceboxtelemetry.ble.RaceBoxService
import com.example.raceboxtelemetry.api.TelemetryApi
import com.example.raceboxtelemetry.preferences.DataFieldPreferences
import com.example.raceboxtelemetry.ui.GForceMeterView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var apiUrlLayout: TextInputLayout
    private lateinit var apiUrlInput: TextInputEditText
    private lateinit var scanButton: MaterialButton
    private lateinit var connectButton: MaterialButton
    private lateinit var disconnectButton: MaterialButton
    private lateinit var zeroGMeterButton: MaterialButton
    private lateinit var statusText: MaterialTextView
    private lateinit var gForceMeter: GForceMeterView
    private lateinit var speedText: MaterialTextView
    private lateinit var gLatText: MaterialTextView
    private lateinit var gLongText: MaterialTextView
    private lateinit var latitudeText: MaterialTextView
    private lateinit var longitudeText: MaterialTextView
    private lateinit var satellitesText: MaterialTextView
    private lateinit var timestampText: MaterialTextView
    private lateinit var devicesRecyclerView: RecyclerView

    private var raceBoxService: RaceBoxService? = null
    private var raceBoxManager: RaceBoxManager? = null
    private var serviceBound = false
    private lateinit var prefs: DataFieldPreferences

    private val deviceAdapter = DeviceAdapter(
        onDeviceClick = { device ->
            raceBoxManager?.connect(device)
        },
        onDeviceLongClick = { device ->
            showDeviceAliasDialog(device)
        }
    )

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RaceBoxService.LocalBinder
            raceBoxService = binder.getService()
            raceBoxManager = raceBoxService?.getRaceBoxManager()
            serviceBound = true
            observeTelemetry()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            raceBoxService = null
            raceBoxManager = null
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Permissions required for BLE", Toast.LENGTH_SHORT).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Disable dynamic colors (Material You) - force our blue theme
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setTheme(R.style.Theme_RaceBoxTelemetry)
        }

        setContentView(R.layout.activity_main)

        // Initialize preferences once
        prefs = DataFieldPreferences(this)

        initializeViews()
        setupClickListeners()
        checkPermissions()
        startAndBindService()
    }

    private fun initializeViews() {
        apiUrlLayout = findViewById(R.id.apiUrlLayout)
        apiUrlInput = findViewById(R.id.apiUrlInput)
        scanButton = findViewById(R.id.scanButton)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        zeroGMeterButton = findViewById(R.id.zeroGMeterButton)
        statusText = findViewById(R.id.statusText)
        gForceMeter = findViewById(R.id.gForceMeter)
        speedText = findViewById(R.id.speedText)
        gLatText = findViewById(R.id.gLatText)
        gLongText = findViewById(R.id.gLongText)
        latitudeText = findViewById(R.id.latitudeText)
        longitudeText = findViewById(R.id.longitudeText)
        satellitesText = findViewById(R.id.satellitesText)
        timestampText = findViewById(R.id.timestampText)
        devicesRecyclerView = findViewById(R.id.devicesRecyclerView)

        devicesRecyclerView.layoutManager = LinearLayoutManager(this)
        devicesRecyclerView.adapter = deviceAdapter

        // Load saved API URL from preferences and apply it
        val savedUrl = prefs.apiUrl
        apiUrlInput.setText(savedUrl)
        TelemetryApi.setBaseUrl(savedUrl)

        // Set up "go" button to apply URL and unfocus
        apiUrlLayout.setEndIconOnClickListener {
            applyApiUrl()
        }

        // Handle "Done" button on keyboard
        apiUrlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyApiUrl()
                true
            } else {
                false
            }
        }

        // Long-press on G-meter to reset max G-force
        gForceMeter.setOnLongClickListener {
            gForceMeter.resetMaxGForce()
            Toast.makeText(this, "Max G-force reset", Toast.LENGTH_SHORT).show()
            true
        }

        // Update field visibility based on preferences
        updateFieldVisibility()
    }

    private fun applyApiUrl() {
        val url = apiUrlInput.text.toString().trim()
        if (url.isNotEmpty()) {
            // Save to preferences
            prefs.apiUrl = url

            // Apply to API
            TelemetryApi.setBaseUrl(url)

            // Send video delay configuration to server
            val videoDelayMs = prefs.videoDelayMs
            lifecycleScope.launch {
                val result = TelemetryApi.updateConfig(videoDelayMs)
                result.onSuccess {
                    Toast.makeText(this@MainActivity, "API URL updated (delay: ${videoDelayMs}ms)", Toast.LENGTH_SHORT).show()
                }
                result.onFailure {
                    Toast.makeText(this@MainActivity, "API URL updated (config sync failed)", Toast.LENGTH_SHORT).show()
                }
            }

            // Clear focus and hide keyboard
            apiUrlInput.clearFocus()
        }
    }

    private fun setupClickListeners() {
        scanButton.setOnClickListener {
            // Ensure API URL is applied
            applyApiUrl()

            if (raceBoxManager?.isBluetoothEnabled() == true) {
                raceBoxManager?.startScan()
            } else {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                enableBluetoothLauncher.launch(enableBtIntent)
            }
        }

        connectButton.setOnClickListener {
            // Reconnect to the last connected device
            val success = raceBoxManager?.reconnectToLastDevice() ?: false
            if (!success) {
                Toast.makeText(this, "No previous device found. Please scan for devices.", Toast.LENGTH_SHORT).show()
            }
        }

        disconnectButton.setOnClickListener {
            raceBoxManager?.disconnect()
        }

        zeroGMeterButton.setOnClickListener {
            raceBoxManager?.zeroGMeter()
            gForceMeter.resetMaxGForce()
            Toast.makeText(this, "G-meter zeroed at current position", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, RaceBoxService::class.java)
        intent.action = RaceBoxService.ACTION_START
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeTelemetry() {
        lifecycleScope.launch {
            raceBoxManager?.connectionState?.collectLatest { state ->
                statusText.text = "Status: ${state.name}"
                disconnectButton.isEnabled = state == RaceBoxManager.ConnectionState.CONNECTED
                scanButton.isEnabled = state == RaceBoxManager.ConnectionState.DISCONNECTED

                // Enable connect button only when disconnected and there's a last device
                val hasLastDevice = raceBoxManager?.hasLastConnectedDevice() ?: false
                connectButton.isEnabled = state == RaceBoxManager.ConnectionState.DISCONNECTED && hasLastDevice
            }
        }

        lifecycleScope.launch {
            raceBoxManager?.telemetryData?.collectLatest { data ->
                // Update G-meter
                gForceMeter.updateGForces(data.gLat.toFloat(), data.gLong.toFloat())

                // Update text displays
                speedText.text = "Speed: %.1f km/h".format(data.speed)
                gLatText.text = "G-Lat: %.2f".format(data.gLat)
                gLongText.text = "G-Long: %.2f".format(data.gLong)
                latitudeText.text = "Latitude: %.6f".format(data.latitude)
                longitudeText.text = "Longitude: %.6f".format(data.longitude)
                satellitesText.text = "Satellites: %d".format(data.satellites)
                timestampText.text = "Timestamp: %s".format(data.timestamp ?: "--")
            }
        }

        lifecycleScope.launch {
            raceBoxManager?.scanResults?.collectLatest { devices ->
                deviceAdapter.submitList(devices, raceBoxManager)
            }
        }
    }

    private fun updateFieldVisibility() {
        val fields = mapOf(
            speedText to prefs.sendSpeed,
            gLatText to prefs.sendGLat,
            gLongText to prefs.sendGLong,
            latitudeText to prefs.sendLatitude,
            longitudeText to prefs.sendLongitude,
            satellitesText to prefs.sendSatellites,
            timestampText to prefs.sendTimestamp
        )
        fields.forEach { (view, enabled) ->
            view.visibility = if (enabled) View.VISIBLE else View.GONE
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun showDeviceAliasDialog(device: BluetoothDevice) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_device_alias, null)

        val deviceInfoText = dialogView.findViewById<MaterialTextView>(R.id.deviceInfoText)
        val aliasInput = dialogView.findViewById<TextInputEditText>(R.id.aliasInput)

        // Set device info
        deviceInfoText.text = "Set alias for ${device.name ?: "Unknown"} (${device.address})"

        // Load existing alias if available
        val currentAlias = raceBoxManager?.getDeviceAlias(device.address)
        if (currentAlias != null) {
            aliasInput.setText(currentAlias)
        }

        AlertDialog.Builder(this)
            .setTitle("Device Alias")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val alias = aliasInput.text.toString().trim()
                if (alias.isNotEmpty()) {
                    raceBoxManager?.saveDeviceAlias(device.address, alias)
                    Toast.makeText(this, "Alias saved", Toast.LENGTH_SHORT).show()
                    // Refresh the device list to show the new alias
                    raceBoxManager?.scanResults?.value?.let { devices ->
                        deviceAdapter.submitList(devices, raceBoxManager)
                    }
                } else {
                    raceBoxManager?.removeDeviceAlias(device.address)
                    Toast.makeText(this, "Alias removed", Toast.LENGTH_SHORT).show()
                    // Refresh the device list
                    raceBoxManager?.scanResults?.value?.let { devices ->
                        deviceAdapter.submitList(devices, raceBoxManager)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Remove") { _, _ ->
                raceBoxManager?.removeDeviceAlias(device.address)
                Toast.makeText(this, "Alias removed", Toast.LENGTH_SHORT).show()
                // Refresh the device list
                raceBoxManager?.scanResults?.value?.let { devices ->
                    deviceAdapter.submitList(devices, raceBoxManager)
                }
            }
            .show()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                showDataFieldsDialog()
                true
            }
            R.id.action_stream_delay -> {
                showStreamDelayDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showDataFieldsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_data_fields, null)

        // Get checkboxes
        val checkboxSpeed = dialogView.findViewById<CheckBox>(R.id.checkboxSpeed)
        val checkboxGLat = dialogView.findViewById<CheckBox>(R.id.checkboxGLat)
        val checkboxGLong = dialogView.findViewById<CheckBox>(R.id.checkboxGLong)
        val checkboxLatitude = dialogView.findViewById<CheckBox>(R.id.checkboxLatitude)
        val checkboxLongitude = dialogView.findViewById<CheckBox>(R.id.checkboxLongitude)
        val checkboxSatellites = dialogView.findViewById<CheckBox>(R.id.checkboxSatellites)
        val checkboxTimestamp = dialogView.findViewById<CheckBox>(R.id.checkboxTimestamp)

        // Set current values
        val checkboxes = mapOf(
            checkboxSpeed to prefs.sendSpeed,
            checkboxGLat to prefs.sendGLat,
            checkboxGLong to prefs.sendGLong,
            checkboxLatitude to prefs.sendLatitude,
            checkboxLongitude to prefs.sendLongitude,
            checkboxSatellites to prefs.sendSatellites,
            checkboxTimestamp to prefs.sendTimestamp
        )
        checkboxes.forEach { (checkbox, value) -> checkbox.isChecked = value }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                // Save preferences
                prefs.sendSpeed = checkboxSpeed.isChecked
                prefs.sendGLat = checkboxGLat.isChecked
                prefs.sendGLong = checkboxGLong.isChecked
                prefs.sendLatitude = checkboxLatitude.isChecked
                prefs.sendLongitude = checkboxLongitude.isChecked
                prefs.sendSatellites = checkboxSatellites.isChecked
                prefs.sendTimestamp = checkboxTimestamp.isChecked

                if (!prefs.hasEnabledFields()) {
                    Toast.makeText(this, "At least one field must be enabled", Toast.LENGTH_SHORT).show()
                    // Re-enable defaults
                    prefs.sendSpeed = true
                    prefs.sendGLat = true
                    prefs.sendGLong = true
                }

                // Update field visibility based on new preferences
                updateFieldVisibility()

                Toast.makeText(this, "Data fields saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateFrequencyLabel(label: TextView, frequencyHz: Int) {
        val intervalMs = 1000 / frequencyHz
        label.text = "$frequencyHz Hz (${intervalMs}ms interval)"
    }

    private fun updateVideoDelayLabel(label: TextView, delayMs: Int) {
        val seconds = delayMs / 1000.0
        label.text = "$delayMs ms (%.1f seconds)".format(seconds)
    }

    // Generic label updater for slider values
    private fun updateSliderLabel(label: TextView, value: Int, formatter: (Int) -> String) {
        label.text = formatter(value)
    }

    private fun showStreamDelayDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_stream_delay, null)

        // Get video delay slider and label
        val videoDelaySlider = dialogView.findViewById<Slider>(R.id.videoDelaySlider)
        val videoDelayLabel = dialogView.findViewById<TextView>(R.id.videoDelayLabel)
        val delayInfoButton = dialogView.findViewById<MaterialButton>(R.id.delayInfoButton)

        // Get frequency slider and label
        val frequencySlider = dialogView.findViewById<Slider>(R.id.frequencySlider)
        val frequencyLabel = dialogView.findViewById<TextView>(R.id.frequencyLabel)
        val frequencyInfoButton = dialogView.findViewById<MaterialButton>(R.id.frequencyInfoButton)

        // Set current video delay from preference
        val currentVideoDelay = prefs.videoDelayMs
        videoDelaySlider.value = currentVideoDelay.toFloat()
        updateVideoDelayLabel(videoDelayLabel, currentVideoDelay)

        // Set current frequency from preference
        val currentFrequency = prefs.getFrequencyHz().toInt()
        frequencySlider.value = currentFrequency.toFloat()
        updateFrequencyLabel(frequencyLabel, currentFrequency)

        // Update video delay label as slider moves
        videoDelaySlider.addOnChangeListener { _, value, _ ->
            updateVideoDelayLabel(videoDelayLabel, value.toInt())
        }

        // Update frequency label as slider moves
        frequencySlider.addOnChangeListener { _, value, _ ->
            updateFrequencyLabel(frequencyLabel, value.toInt())
        }

        // Delay info button
        delayInfoButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Video Stream Delay")
                .setMessage("Sync telemetry overlay with RTMP stream delay. Adjust this value to match the latency of your video stream in OBS.\n\n" +
                        "How to find your delay:\n" +
                        "1. Start your RTMP stream to OBS\n" +
                        "2. Hard brake while driving\n" +
                        "3. Watch when the G-meter reacts vs. the video\n" +
                        "4. Adjust until they match\n\n" +
                        "Typical values:\n" +
                        "• 360p local: 800-1200ms\n" +
                        "• 360p network: 1500-2000ms\n" +
                        "• 720p/1080p: 2000-3000ms")
                .setPositiveButton("OK", null)
                .show()
        }

        // Frequency info button
        frequencyInfoButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Send Frequency")
                .setMessage("Controls how often the Android app sends telemetry data to the server.\n\n" +
                        "Higher frequency = smoother overlay but more network/battery usage\n" +
                        "Lower frequency = less smooth but more efficient\n\n" +
                        "Recommended:\n" +
                        "• 10Hz (100ms) - Good balance\n" +
                        "• 6Hz (166ms) - Conservative/battery saving\n" +
                        "• 20Hz (50ms) - Very smooth (high bandwidth)")
                .setPositiveButton("OK", null)
                .show()
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                // Save video delay
                val videoDelayMs = videoDelaySlider.value.toInt()
                prefs.videoDelayMs = videoDelayMs

                // Save frequency
                val frequencyHz = frequencySlider.value.toInt()
                prefs.sendIntervalMs = (1000L / frequencyHz)

                // Push both settings to server
                lifecycleScope.launch {
                    val result = TelemetryApi.updateConfig(
                        videoDelayMs = videoDelayMs,
                        sendFrequencyHz = frequencyHz
                    )
                    result.onSuccess {
                        Log.d("MainActivity", "✓ Settings pushed to server: ${videoDelayMs}ms delay, ${frequencyHz}Hz")
                        Toast.makeText(this@MainActivity, "Settings updated: ${videoDelayMs}ms, ${frequencyHz}Hz", Toast.LENGTH_SHORT).show()
                    }
                    result.onFailure { e ->
                        Log.w("MainActivity", "Failed to push settings to server: ${e.message}")
                        Toast.makeText(this@MainActivity, "Settings saved (server update failed)", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}

class DeviceAdapter(
    private val onDeviceClick: (BluetoothDevice) -> Unit,
    private val onDeviceLongClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    private var devices = listOf<BluetoothDevice>()
    private var raceBoxManager: RaceBoxManager? = null

    fun submitList(newDevices: List<BluetoothDevice>, manager: RaceBoxManager? = null) {
        devices = newDevices
        raceBoxManager = manager
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): DeviceViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount() = devices.size

    inner class DeviceViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val text1: android.widget.TextView = itemView.findViewById(android.R.id.text1)
        private val text2: android.widget.TextView = itemView.findViewById(android.R.id.text2)

        @android.annotation.SuppressLint("MissingPermission")
        fun bind(device: BluetoothDevice) {
            // Try to get alias, otherwise use device name
            val alias = raceBoxManager?.getDeviceAlias(device.address)
            val deviceName = device.name ?: "Unknown"

            text1.text = if (alias != null) {
                "$alias ($deviceName)"
            } else {
                deviceName
            }
            text2.text = device.address
            itemView.setOnClickListener { onDeviceClick(device) }
            itemView.setOnLongClickListener {
                onDeviceLongClick(device)
                true
            }
        }
    }
}
