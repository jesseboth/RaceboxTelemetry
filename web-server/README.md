# RaceBox Telemetry Server

A Node.js Express server for receiving, storing, and displaying RaceBox telemetry data from the Android app.

## Features

- **REST API**: Receives telemetry data from the Android app via POST /telemetry
- **Real-time Overlay**: Serves a web-based overlay displaying speed and G-forces
- **Session Management**: Automatically tracks telemetry sessions with timeout-based auto-save
- **Data Archiving**: Saves session data to archive directory organized by date
- **Docker Support**: Easy deployment with Docker container and management script

## Quick Start

### Using Docker (Recommended)

1. Start the server:
```bash
./docker.sh daemon
```

2. Access the overlay at: http://localhost:5000

3. Access admin settings at: http://localhost:5000/admin

4. Configure your Android app to send data to: http://YOUR_IP:5000/telemetry

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
# Start server (daemon mode)
./docker.sh daemon

# Start with custom port
./docker.sh -p 8080 daemon

# Start with debug logging
./docker.sh --debug daemon

# Start with custom video delay (for stream sync)
./docker.sh --delay 2000 daemon

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
Get configuration for overlay.

**Response:**
```json
{
  "video_delay_ms": 1500,
  "update_interval_ms": 100,
  "session": {
    "id": "uuid-session-id",
    "startTime": "2024-01-15T10:00:00Z",
    "dataPoints": 1234
  }
}
```

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

1. Start your RTMP stream and overlay
2. Do something visible (like hitting the brakes hard)
3. Watch for when the G-force meter reacts vs. when you see it in OBS
4. Adjust the delay until they match:
   - If overlay reacts BEFORE video → increase delay
   - If overlay reacts AFTER video → decrease delay

Typical delays:
- Local RTMP: 1000-1500ms
- Network RTMP: 2000-3000ms
- YouTube/Twitch: 3000-5000ms

## Environment Variables

- `PORT` - Server port (default: 5000)
- `DEBUG` - Enable debug logging (default: false)
- `VIDEO_DELAY_MS` - Video delay in milliseconds for stream sync (default: 1500)

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
- Speed (MPH, converted from km/h)
- G-force visualization (circular meter)
- Maximum G-force recorded
- Connection status indicator

The overlay uses WebSocket for real-time updates and automatically buffers telemetry data to sync with video stream delay (configurable via `VIDEO_DELAY_MS`).

## Admin Settings

Access the admin page at `/admin` to:
- **Adjust video delay** - Real-time slider to sync overlay with stream (0-5000ms)
- **View server status** - Session info, data points, uptime
- **Get overlay URL** - Easy copy/paste for OBS browser source
- **See tips** - Instructions for finding the right delay value

Perfect for stream moderators who need to adjust sync without restarting the server or changing code.

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

**Overlay shows no data:**
- Verify the Android app is sending data (check /session endpoint)
- Check browser console for errors
- Ensure the API_URL in index.html matches your server

**Docker container won't start:**
- Check if port is already in use: `lsof -i :5000`
- View container logs: `./docker.sh log`
- Rebuild image: `./docker.sh build`

## License

ISC
