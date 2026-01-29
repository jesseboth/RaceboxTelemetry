# RaceBox Telemetry Server

A Node.js Express server for receiving, storing, and displaying RaceBox telemetry data from the Android app, with RTMP video streaming support.

## Features

- **REST API**: Receives telemetry data from the Android app via POST /telemetry
- **Real-time Overlay**: Serves a web-based overlay displaying speed and G-forces with WebSocket updates
- **RTMP Streaming**: Built-in nginx-rtmp server for video streaming from phone to OBS
- **Stream Authentication**: Secure RTMP streams with stream key validation
- **Session Management**: Automatically tracks telemetry sessions with timeout-based auto-save
- **Data Archiving**: Saves session data to archive directory organized by date
- **Live Admin Panel**: Real-time configuration of delays, frequency, and max G-force reset interval
- **Max G-Force Tracking**: Automatic reset of max G-force after configurable interval (1-10 minutes)
- **Docker Support**: Easy deployment with Docker container and management script

## Quick Start

### Using Docker (Recommended)

1. **Set up RTMP push configuration** (for video streaming to OBS):
```bash
cp rtmp-push.conf.example rtmp-push.conf
# Edit rtmp-push.conf with your OBS machine IP
```

2. **Start the server with a secure stream key**:
```bash
./docker.sh --stream-key "mySecretKey123" daemon
```

3. **Access the web interfaces**:
   - Overlay: http://localhost:5000
   - Admin Panel: http://localhost:5000/admin
   - Nginx Stats: http://localhost:5001/stat

4. **Configure your Android app**:
   - API URL: `http://YOUR_SERVER_IP:5000/telemetry`

5. **Configure your phone camera app** (for RTMP streaming):
   - RTMP URL: `rtmp://YOUR_SERVER_IP:1935/live/mySecretKey123`

6. **Configure OBS** (Media Source):
   - Input: `rtmp://YOUR_SERVER_IP:1935/live/stream`
   - Or start OBS as RTMP server (see RTMP Setup section)

### Using Node.js Directly

1. Install dependencies:
```bash
npm install
```

2. Start the server:
```bash
npm start
```

Or specify a custom port:
```bash
PORT=8080 npm start
```

## Docker Commands

```bash
# Start server (daemon mode) with secure stream key
./docker.sh --stream-key "mySecretKey123" daemon

# Start with custom port
./docker.sh -p 8080 daemon

# Start with debug logging
./docker.sh --debug daemon

# Start with custom video delay (for stream sync)
./docker.sh --delay 2000 daemon

# Start with all custom options
./docker.sh --stream-key "myKey" --delay 2000 --debug daemon

# Start with host networking
./docker.sh --network host daemon

# View logs
./docker.sh log

# Stop server
./docker.sh stop

# Restart server
./docker.sh restart

# Build Docker image
./docker.sh build
```

## RTMP Streaming Setup

The server includes a built-in nginx-rtmp server for video streaming with authentication.

### Ports
- **5000**: Web server (telemetry API, overlay, admin panel)
- **1935**: RTMP server (video streaming)
- **5001**: Nginx stats and API

### Stream Key Authentication

Secure your RTMP stream with a stream key to prevent unauthorized access.

**Set stream key when starting:**
```bash
./docker.sh --stream-key "mySecretKey123" daemon
```

**Default key** (not secure - change for production):
```
racebox-default-key
```

### Phone Configuration (Streamlabs, Larix, etc.)

Configure your streaming app to publish to:
```
rtmp://[SERVER_IP]:1935/live/[YOUR_STREAM_KEY]
```

**Examples:**
- With custom key: `rtmp://192.168.1.100:1935/live/mySecretKey123`
- With default key: `rtmp://192.168.1.100:1935/live/racebox-default-key`

Only streams with the correct key will be accepted. Invalid keys are rejected immediately.

### OBS Configuration

**Option 1: OBS as RTMP Server (Receives stream from nginx)**

1. Copy and edit the push configuration:
```bash
cp rtmp-push.conf.example rtmp-push.conf
# Edit: push rtmp://[OBS_MACHINE_IP]:1935/live/stream;
```

2. In OBS, set up Media Source:
   - Input: `rtmp://0.0.0.0:1935/live/stream`
   - Or if nginx is on another machine: `rtmp://[SERVER_IP]:1935/live/stream`

**Option 2: OBS on Same Machine**

Add Media Source with input:
```
rtmp://localhost:1935/live/stream
```

### Stream Flow

```
Phone (Streamlabs/Larix)
    ↓ publishes with stream key
rtmp://server:1935/live/[key]
    ↓ validates key
nginx-rtmp (authenticates)
    ↓ relays to
OBS (receives stream)
```

### Checking Stream Status

View nginx RTMP statistics:
```
http://localhost:5001/stat
```

## API Endpoints

### POST /telemetry
Receive telemetry data from the Android app.

**Request Body:**
```json
{
  "speed": 120.5,
  "g_lat": 0.85,
  "g_long": -0.45,
  "latitude": 42.123456,
  "longitude": -76.123456,
  "satellites": 12,
  "timestamp": "2024-01-15T10:30:00Z"
}
```

**Response:**
```json
{
  "status": "ok",
  "session": "uuid-session-id",
  "dataPoints": 1234
}
```

### GET /telemetry
Get the latest telemetry data (used by overlay).

**Response:**
```json
{
  "speed": 120.5,
  "g_lat": 0.85,
  "g_long": -0.45,
  "latitude": 42.123456,
  "longitude": -76.123456,
  "satellites": 12,
  "timestamp": "2024-01-15T10:30:00Z",
  "_lastUpdate": "2024-01-15T10:30:01Z"
}
```

### GET /config
Get configuration for overlay and Android app.

**Response:**
```json
{
  "video_delay_ms": 1500,
  "send_frequency_hz": 10,
  "send_interval_ms": 100,
  "update_interval_ms": 100,
  "max_g_reset_interval_min": 5,
  "session": {
    "id": "uuid-session-id",
    "startTime": "2024-01-15T10:00:00Z",
    "dataPoints": 1234
  }
}
```

### POST /config
Update server configuration (video delay, send frequency, max G reset interval).

**Request Body:**
```json
{
  "video_delay_ms": 2000,
  "send_frequency_hz": 20,
  "max_g_reset_interval_min": 3
}
```

**Response:**
```json
{
  "status": "ok",
  "video_delay_ms": 2000,
  "send_frequency_hz": 20,
  "send_interval_ms": 50,
  "max_g_reset_interval_min": 3
}
```

All connected overlays receive the update via WebSocket immediately.

### GET /session
Get current session information.

**Response:**
```json
{
  "id": "uuid-session-id",
  "startTime": "2024-01-15T10:00:00Z",
  "dataPoints": 1234,
  "latestData": { ... }
}
```

### POST /session/end
Manually end the current session and save to archive.

**Response:**
```json
{
  "status": "ok",
  "message": "Session ended",
  "sessionId": "uuid-session-id"
}
```

### GET /rtmp/validate
Internal endpoint called by nginx to validate stream keys.

**Query Parameters:**
- `name`: Stream name/key from RTMP URL

**Response:**
- `200 OK`: Valid stream key, allow publish
- `403 Forbidden`: Invalid stream key, deny publish

### GET /rtmp/sync
Query RTMP stream synchronization info.

**Response:**
```json
{
  "rtmp": {
    "is_live": true,
    "stream_start_time_unix_ms": 1640000000000,
    "stream_name": "stream"
  },
  "sync_available": true,
  "video_delay_ms": 1500,
  "telemetry_delay_ms": 1234
}
```

### GET /archive
List all archived sessions.

**Response:**
```json
{
  "sessions": [
    {
      "filename": "2024-01-15_uuid.json",
      "path": "2024/01/2024-01-15_uuid.json",
      "session": {
        "id": "uuid",
        "startTime": "2024-01-15T10:00:00Z",
        "endTime": "2024-01-15T11:00:00Z",
        "duration": 3600000,
        "dataPoints": 36000
      },
      "dataPoints": 36000
    }
  ]
}
```

### GET /archive/:year/:month/:filename
Get a specific archived session.

**Response:**
```json
{
  "session": {
    "id": "uuid",
    "startTime": "2024-01-15T10:00:00Z",
    "endTime": "2024-01-15T11:00:00Z",
    "duration": 3600000,
    "dataPoints": 36000
  },
  "data": [
    {
      "speed": 120.5,
      "g_lat": 0.85,
      "g_long": -0.45,
      "_received": "2024-01-15T10:00:01Z"
    },
    ...
  ]
}
```

### GET /health
Health check endpoint.

**Response:**
```json
{
  "status": "ok",
  "uptime": 3600.123,
  "session": "uuid-session-id"
}
```

## Directory Structure

```
web-server/
├── server.js           # Main Express server
├── package.json        # Node.js dependencies
├── Dockerfile          # Docker image definition
├── docker.sh           # Docker management script
├── README.md           # This file
├── public/             # Static web files
│   └── index.html      # Telemetry overlay (styled_overlay.html)
├── data/               # Configuration files
└── archive/            # Archived session data
    └── YYYY/           # Year
        └── MM/         # Month
            └── YYYY-MM-DD_uuid.json  # Session data
```

## Session Management

- **Auto-start**: A new session starts automatically when the first telemetry data is received
- **Auto-end**: Sessions automatically end after 5 minutes of inactivity
- **Auto-save**: Ended sessions are automatically saved to the archive directory
- **Manual end**: Sessions can be manually ended via POST /session/end

## Archive Storage

Archived sessions are stored in the following structure:
```
archive/
└── 2024/
    └── 01/
        ├── 2024-01-15_abc123.json
        ├── 2024-01-15_def456.json
        └── 2024-01-16_ghi789.json
```

Each archived session file contains:
- Session metadata (ID, start/end time, duration, data point count)
- Full telemetry data array with receive timestamps

## Video Stream Synchronization

When streaming video from your phone to OBS via RTMP and using the telemetry overlay as a browser source, you need to sync the telemetry display with the video delay.

### How It Works

1. **Telemetry arrives instantly** - Data from your phone reaches the server in milliseconds
2. **Video has latency** - RTMP streaming typically adds 1-3 seconds of delay
3. **Buffer & Delay** - The overlay buffers incoming telemetry and displays it with a configurable delay to match your video

### Configuration

**Set delay via environment variable:**
```bash
VIDEO_DELAY_MS=2000 npm start
```

**Set delay via Docker:**
```bash
./docker.sh --delay 2000 daemon
```

**Override delay for testing (URL parameter):**
```
http://localhost:5000?delay=2500
```

### Finding Your Delay

**Method 1: Admin Page (Recommended)**
1. Open `http://YOUR_SERVER:5000/admin` in a browser
2. Start your RTMP stream and add overlay as OBS browser source
3. Hard brake while driving
4. Use the slider to adjust delay until video and overlay sync
5. Changes apply instantly to all overlays - no refresh needed!

**Method 2: URL Parameter (Testing)**
```
http://localhost:5000?delay=2500
```

**Method 3: Environment Variable (Permanent)**
```bash
VIDEO_DELAY_MS=2000 npm start
# or
./docker.sh --delay 2000 daemon
```

Typical delays:
- Local RTMP: 1000-1500ms
- Network RTMP: 2000-3000ms
- YouTube/Twitch: 3000-5000ms

## Environment Variables

- `PORT` - Server port (default: 5000)
- `DEBUG` - Enable debug logging (default: false)
- `VIDEO_DELAY_MS` - Video delay in milliseconds for stream sync (default: 1500)
- `SEND_FREQUENCY_HZ` - Android app send frequency (default: 10 Hz)
- `MAX_G_RESET_INTERVAL_MIN` - Max G-force auto-reset interval in minutes (default: 5)
- `STREAM_KEY` - RTMP stream key for authentication (default: racebox-default-key)

**Set via Docker:**
```bash
./docker.sh --stream-key "myKey" --delay 2000 daemon
```

**Set via environment:**
```bash
PORT=8080 STREAM_KEY="myKey" DEBUG=true npm start
```

## Android App Configuration

Configure the Android app to send telemetry data to:
```
http://YOUR_SERVER_IP:5000/telemetry
```

Replace `YOUR_SERVER_IP` with:
- Local network: Your computer's local IP (e.g., 192.168.1.100)
- Same device: localhost or 127.0.0.1
- Remote: Your server's public IP or domain

## Overlay Usage

The telemetry overlay is accessible at the server root URL and displays:
- **Speed**: MPH (converted from km/h)
- **G-force meter**: Circular visualization with real-time position
- **Maximum G-force**: Ghosted indicator showing peak G-force
- **Auto-reset**: Max G-force resets automatically after configured interval (1-10 minutes)
- **Connection status**: Color-coded indicator (green=connected, red=offline, cyan=synced)

### Features
- **WebSocket**: Real-time updates with minimal latency
- **Delay buffering**: Automatically syncs telemetry with video stream delay
- **Max G tracking**: Shows both current and maximum G-forces with auto-reset
- **Responsive**: Works on any screen size

### Add to OBS
1. Add Browser Source
2. URL: `http://YOUR_SERVER_IP:5000`
3. Width: 1920, Height: 1080 (or your stream resolution)
4. Custom CSS: (optional) adjust positioning if needed

## Admin Settings

Access the admin page at `/admin` to configure everything in real-time:

### Video Stream Delay
- **Slider**: 0-5000ms (adjusts overlay sync with video stream)
- **Live updates**: All connected overlays update instantly via WebSocket
- **No refresh needed**: Changes apply immediately to all active overlays

### Android App Send Frequency
- **Slider**: 1-25 Hz (controls how often Android app sends telemetry)
- **Trade-off**: Higher frequency = smoother overlay, more battery/network usage
- **Recommended**: 10 Hz (100ms interval)

### Max G-Force Auto-Reset
- **Slider**: 1-10 minutes
- **Behavior**: Max G-force automatically resets after this interval of inactivity
- **Timer resets**: When a new max G is recorded, the timer starts over
- **Default**: 5 minutes

### Server Information
- **Session Status**: Active or Idle
- **Data Points**: Number of telemetry points in current session
- **Server Uptime**: How long the server has been running

### Features
- **Real-time sync**: All settings update live across all overlays
- **No restarts needed**: Adjust everything on-the-fly
- **Perfect for streams**: Change sync mid-stream without interruption

## Development

Run in debug mode:
```bash
npm run debug
```

Or with Docker:
```bash
./docker.sh --debug daemon
```

Debug mode enables console logging for all incoming requests and session events.

## Docker Volume Mounts

The Docker container automatically mounts:
- `./archive` -> `/usr/src/app/archive` (for persistent session storage)

This ensures archived session data persists even when the container is stopped or removed.

## Timezone

The Docker container is configured for the America/New_York timezone. To change this, edit the `TZ` environment variable in the Dockerfile.

## Troubleshooting

**Android app can't connect:**
- Ensure both devices are on the same network
- Check firewall settings on the server
- Verify the IP address and port are correct
- Test with: `curl http://SERVER_IP:5000/health`

**RTMP stream rejected:**
- Check the stream key matches what's configured on the server
- View server logs: `./docker.sh log`
- Look for: `✗ Invalid stream key - denying publish`
- Ensure you're publishing to: `rtmp://server:1935/live/[YOUR_KEY]`

**RTMP stream not reaching OBS:**
- Verify `rtmp-push.conf` exists and contains correct OBS IP
- Check OBS is set up to receive RTMP stream
- View nginx stats: `http://localhost:5001/stat`
- Check nginx logs in Docker container

**Overlay shows no data:**
- Verify the Android app is sending data (check /session endpoint)
- Check browser console for errors
- Ensure WebSocket connection is established

**Overlay not synced with video:**
- Use the Admin Panel (`/admin`) to adjust video delay
- Try the slider while watching - changes apply instantly
- Typical values: 1000-3000ms depending on stream quality

**Docker container won't start:**
- Port 5000 in use: `lsof -i :5000`
- Port 1935 in use: `lsof -i :1935`
- Port 5001 in use: `lsof -i :5001`
- View logs: `./docker.sh log`
- Rebuild: `./docker.sh build`

**Max G-force not resetting:**
- Check the reset interval in Admin Panel
- Timer resets when a new max G is recorded
- View browser console for "Auto-resetting max g-force" messages

## License

ISC
