# RaceBox Telemetry Streamer

Android app that connects to a RaceBox Mini or Micro device via Bluetooth and streams telemetry data (speed, lateral g-force, longitudinal g-force) to a custom API endpoint.

## Features

- Bluetooth Low Energy (BLE) connection to RaceBox devices
- Real-time telemetry streaming at 10Hz
- Configurable API endpoint
- Foreground service for reliable connection
- Simple UI showing live telemetry data

## Requirements

- Android 8.0 (API 26) or higher
- RaceBox Mini or Micro device
- Bluetooth permissions
- Location permissions (required for BLE scanning)
- Internet connection to reach API endpoint

## Setup

### 1. Build the App

Open the project in Android Studio:
```bash
cd ~/Develop/RaceboxTelemetry
# Open in Android Studio
```

Or build from command line:
```bash
./gradlew assembleDebug
```

### 2. Install on Device

```bash
./gradlew installDebug
```

### 3. Configure API Endpoint

The app sends telemetry data to your specified endpoint. By default, it's set to:
```
http://192.168.1.100:5000
```

You need a server listening on that endpoint that accepts POST requests to `/telemetry` with this JSON format:

```json
{
  "speed": 45.5,
  "g_lat": 0.85,
  "g_long": -0.32,
  "timestamp": 1706234567890,
  "latitude": 37.7749,
  "longitude": -122.4194,
  "satellites": 12
}
```

## API Server Example

Here's a simple Flask server to receive the data:

```python
from flask import Flask, request, jsonify
from flask_cors import CORS

app = Flask(__name__)
CORS(app)

current_data = {}

@app.route('/telemetry', methods=['POST'])
def receive_telemetry():
    global current_data
    current_data = request.json
    print(f"Speed: {current_data.get('speed')} km/h, "
          f"G-Lat: {current_data.get('g_lat')}, "
          f"G-Long: {current_data.get('g_long')}")
    return jsonify({"status": "ok"}), 200

@app.route('/telemetry', methods=['GET'])
def get_telemetry():
    return jsonify(current_data)

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)
```

## Usage

1. **Launch the app** on your Android device
2. **Enter API endpoint URL** in the text field
3. **Tap "Scan for RaceBox"** to start scanning for devices
4. **Select your RaceBox** from the list of found devices
5. **Wait for connection** - status will show "CONNECTED"
6. **Telemetry streams automatically** to your API endpoint at 10Hz

The app runs as a foreground service with a notification, so it continues working even when the app is in the background.

## BLE Protocol Details

The app uses the RaceBox BLE protocol based on Nordic UART Service:

- **UART Service UUID**: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
- **RX Characteristic**: `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` (write commands)
- **TX Characteristic**: `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` (receive data)

Data is received as:
- NMEA sentences (GPS data)
- Binary telemetry (acceleration data)

## Troubleshooting

### No devices found
- Ensure Bluetooth is enabled
- Grant location permissions (required for BLE scanning on Android)
- Make sure RaceBox is powered on and not connected to another device

### Connection fails
- RaceBox can only connect to one device at a time
- Disconnect from SoloStorm or other apps first
- Try restarting Bluetooth on your phone

### API errors
- Check network connectivity
- Verify API endpoint URL is correct
- Ensure server is running and accessible
- Check firewall settings

### Data looks wrong
- RaceBox outputs speed in km/h
- G-forces are in units of gravity (1.0 = 1G)
- Data parsing depends on RaceBox firmware version

## Development

### Project Structure

```
app/src/main/java/com/example/raceboxtelemetry/
├── MainActivity.kt              # Main UI
├── api/
│   └── TelemetryApi.kt         # Retrofit API client
├── ble/
│   ├── RaceBoxManager.kt       # BLE connection manager
│   └── RaceBoxService.kt       # Foreground service
└── model/
    └── TelemetryData.kt        # Data models
```

### Key Classes

- **RaceBoxManager**: Handles BLE scanning, connection, and data parsing
- **RaceBoxService**: Foreground service that maintains connection and sends data
- **TelemetryApi**: Retrofit client for HTTP requests
- **MainActivity**: UI for configuration and monitoring

## Protocol Documentation

For complete RaceBox BLE protocol details, see:
https://www.racebox.pro/products/mini-micro-protocol-documentation

## License

This is example code for educational purposes. Adjust as needed for your use case.

## Credits

- RaceBox BLE protocol information from [RaceBox Official Documentation](https://www.racebox.pro/products/mini-micro-protocol-documentation)
- Uses Nordic UART Service (NUS) standard UUIDs
