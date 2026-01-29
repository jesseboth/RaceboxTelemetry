const express = require('express');
const cors = require('cors');
const path = require('path');
const fs = require('fs');
const { v4: uuidv4 } = require('uuid');
const WebSocket = require('ws');
const http = require('http');
const fetch = require('node-fetch');

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

const PORT = process.env.PORT || 5000;
const DEBUG = process.env.DEBUG === 'true';
let VIDEO_DELAY_MS = parseInt(process.env.VIDEO_DELAY_MS) || 1000; // Default 1000ms (optimized for 360p RTMP over ethernet)
let SEND_FREQUENCY_HZ = parseInt(process.env.SEND_FREQUENCY_HZ) || 10; // Default 10Hz
let MAX_G_RESET_INTERVAL_MIN = parseInt(process.env.MAX_G_RESET_INTERVAL_MIN) || 5; // Default 5 minutes
const STREAM_KEY = process.env.STREAM_KEY || 'racebox-default-key'; // Change this for security!

// Middleware
app.use(cors());
app.use(express.json());

// Request logger (only in debug mode)
if (DEBUG) {
    app.use((req, res, next) => {
        const timestamp = new Date().toISOString();
        console.log(`[${timestamp}] ${req.method} ${req.path}`);
        if (req.body && Object.keys(req.body).length > 0) {
            log('Request body:', req.body);
        }
        next();
    });
}

app.use(express.static('public'));
app.use('/archive', express.static('archive'));

// In-memory storage for latest telemetry data
let latestTelemetry = {
    speed: 0,
    g_lat: 0,
    g_long: 0,
    latitude: null,
    longitude: null,
    satellites: null,
    timestamp: null,
    _lastUpdate: null
};

// WebSocket connections
const wsClients = new Set();

// WebSocket connection handler
wss.on('connection', (ws) => {
    wsClients.add(ws);
    log('WebSocket client connected. Total clients:', wsClients.size);

    // Send current telemetry immediately on connect
    ws.send(JSON.stringify({ type: 'telemetry', data: latestTelemetry }));

    ws.on('close', () => {
        wsClients.delete(ws);
        log('WebSocket client disconnected. Total clients:', wsClients.size);
    });

    ws.on('error', (error) => {
        log('WebSocket error:', error.message);
        wsClients.delete(ws);
    });
});

// Broadcast message to all connected WebSocket clients
function broadcastMessage(message) {
    if (wsClients.size === 0) {
        return;
    }

    const messageStr = JSON.stringify(message);
    wsClients.forEach((client) => {
        if (client.readyState === WebSocket.OPEN) {
            client.send(messageStr);
        }
    });
}

// Broadcast telemetry to all connected WebSocket clients
function broadcastTelemetry(data) {
    if (wsClients.size === 0) {
        log('No WebSocket clients connected - data not broadcasted');
        return;
    }

    const message = JSON.stringify({ type: 'telemetry', data });
    let sentCount = 0;
    wsClients.forEach((client) => {
        if (client.readyState === WebSocket.OPEN) {
            client.send(message);
            sentCount++;
        }
    });
    log(`Broadcasted telemetry to ${sentCount}/${wsClients.size} clients`);
}

// Broadcast config update to all connected WebSocket clients
function broadcastConfig() {
    const message = JSON.stringify({
        type: 'config',
        data: {
            video_delay_ms: VIDEO_DELAY_MS,
            send_frequency_hz: SEND_FREQUENCY_HZ,
            send_interval_ms: Math.round(1000 / SEND_FREQUENCY_HZ),
            max_g_reset_interval_min: MAX_G_RESET_INTERVAL_MIN
        }
    });
    wsClients.forEach((client) => {
        if (client.readyState === WebSocket.OPEN) {
            client.send(message);
        }
    });
    log('Config broadcast to', wsClients.size, 'clients');
}

// Session management
let currentSession = null;
let sessionData = [];
const SESSION_TIMEOUT = 300000; // 5 minutes of inactivity ends session
let sessionTimer = null;

// Debug logging
function log(...args) {
    if (DEBUG) {
        console.log('[DEBUG]', new Date().toISOString(), ...args);
    }
}

// Start a new telemetry session
async function startSession() {
    if (!currentSession) {
        currentSession = {
            id: uuidv4(),
            startTime: new Date().toISOString(),
            startTime_unix_ms: Date.now(),
            dataPoints: 0
        };
        sessionData = [];
        console.log('=== NEW SESSION STARTED ===');
        log('Session ID:', currentSession.id);
        log('Start time:', currentSession.startTime);
        log('Server time (ms):', currentSession.startTime_unix_ms);

        // Try to capture RTMP stream start time
        try {
            const response = await fetch('http://localhost:8080/stream/info', { timeout: 500 });
            if (response.ok) {
                const rtmpInfo = await response.json();
                if (rtmpInfo.is_live) {
                    currentSession.rtmp_stream_start_ms = rtmpInfo.stream_start_time_unix_ms;
                    log('RTMP stream active - captured start time:', rtmpInfo.stream_start_time_unix_ms);
                }
            }
        } catch (error) {
            log('Could not query RTMP status:', error.message);
        }

        // Broadcast session start to clients (so they can reset their max g-force)
        broadcastMessage({ type: 'session-start', sessionId: currentSession.id });
    }
}

// End current session and save to archive
function endSession() {
    if (currentSession && sessionData.length > 0) {
        console.log('=== ENDING SESSION ===');
        log('Session ID:', currentSession.id);
        log('Data points collected:', sessionData.length);

        const sessionInfo = {
            ...currentSession,
            endTime: new Date().toISOString(),
            duration: Date.now() - new Date(currentSession.startTime).getTime(),
            dataPoints: sessionData.length
        };

        log('Session duration:', sessionInfo.duration, 'ms');

        // Save to archive
        const date = new Date();
        const year = date.getFullYear();
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const day = String(date.getDate()).padStart(2, '0');

        const archiveDir = path.join(__dirname, 'archive', String(year), month);

        if (!fs.existsSync(archiveDir)) {
            log('Creating archive directory:', archiveDir);
            fs.mkdirSync(archiveDir, { recursive: true });
        }

        const filename = `${year}-${month}-${day}_${currentSession.id}.json`;
        const filepath = path.join(archiveDir, filename);

        const archiveData = {
            session: sessionInfo,
            data: sessionData
        };

        log('Saving session to:', filepath);
        fs.writeFileSync(filepath, JSON.stringify(archiveData, null, 2));
        console.log('✓ Session saved to archive');

        currentSession = null;
        sessionData = [];
    } else if (currentSession) {
        log('Session has no data, not archiving');
        currentSession = null;
        sessionData = [];
    }
}

// Reset session timeout
function resetSessionTimeout() {
    if (sessionTimer) {
        clearTimeout(sessionTimer);
    }
    sessionTimer = setTimeout(() => {
        log('Session timeout - ending session');
        endSession();
    }, SESSION_TIMEOUT);
}

// API Routes

// POST /telemetry - Receive telemetry data from Android app
app.post('/telemetry', (req, res) => {
    const data = req.body;

    log('Received telemetry data:', data);

    const receivedAt_ms = Date.now();

    // Update latest telemetry
    latestTelemetry = {
        speed: data.speed !== undefined ? data.speed : latestTelemetry.speed,
        g_lat: data.g_lat !== undefined ? data.g_lat : latestTelemetry.g_lat,
        g_long: data.g_long !== undefined ? data.g_long : latestTelemetry.g_long,
        latitude: data.latitude !== undefined ? data.latitude : latestTelemetry.latitude,
        longitude: data.longitude !== undefined ? data.longitude : latestTelemetry.longitude,
        satellites: data.satellites !== undefined ? data.satellites : latestTelemetry.satellites,
        timestamp: data.timestamp !== undefined ? data.timestamp : latestTelemetry.timestamp,
        _lastUpdate: new Date().toISOString()
    };

    log('Updated telemetry state:', latestTelemetry);

    // Broadcast to WebSocket clients
    broadcastTelemetry(latestTelemetry);

    // Session management
    startSession();

    // Detect clock offset on first telemetry point with timestamp
    if (currentSession.dataPoints === 0 && data.timestamp) {
        try {
            const phoneTime_ms = new Date(data.timestamp).getTime();
            const clockOffset_ms = receivedAt_ms - phoneTime_ms;
            currentSession.clock_offset_ms = clockOffset_ms;
            log('Clock offset detected:', clockOffset_ms, 'ms (server ahead of phone)');

            // If offset is significant, log a warning
            if (Math.abs(clockOffset_ms) > 5000) {
                console.log(`⚠️  WARNING: Phone clock is ${Math.abs(clockOffset_ms)}ms ${clockOffset_ms > 0 ? 'behind' : 'ahead of'} server`);
            }
        } catch (error) {
            log('Could not detect clock offset:', error.message);
        }
    }

    currentSession.dataPoints++;
    sessionData.push({
        ...data,
        _received: new Date().toISOString(),
        _received_ms: receivedAt_ms
    });
    resetSessionTimeout();

    log('Session updated:', currentSession.id, 'dataPoints:', currentSession.dataPoints);

    res.json({
        status: 'ok',
        session: currentSession.id,
        dataPoints: currentSession.dataPoints
    });
});

// GET /telemetry - Get latest telemetry data (for overlay)
app.get('/telemetry', (req, res) => {
    log('Serving telemetry:', latestTelemetry);
    res.json(latestTelemetry);
});

// GET /config - Configuration for overlay and Android app
app.get('/config', async (req, res) => {
    log('Config requested, session:', currentSession);

    const config = {
        video_delay_ms: VIDEO_DELAY_MS,
        send_frequency_hz: SEND_FREQUENCY_HZ,
        send_interval_ms: Math.round(1000 / SEND_FREQUENCY_HZ),
        update_interval_ms: 100,
        max_g_reset_interval_min: MAX_G_RESET_INTERVAL_MIN,
        session: currentSession
    };

    // Optionally include RTMP sync info if requested
    if (req.query.include_rtmp === 'true') {
        try {
            const response = await fetch('http://localhost:8080/stream/info', { timeout: 500 });
            if (response.ok) {
                const rtmpInfo = await response.json();
                config.rtmp_sync = {
                    is_live: rtmpInfo.is_live,
                    stream_start_time_unix_ms: rtmpInfo.stream_start_time_unix_ms
                };
            }
        } catch (error) {
            log('Could not fetch RTMP info for config:', error.message);
        }
    }

    res.json(config);
});

// POST /config - Update configuration
app.post('/config', (req, res) => {
    const { video_delay_ms, send_frequency_hz, max_g_reset_interval_min } = req.body;
    let updated = false;
    const response = { status: 'ok' };

    if (video_delay_ms !== undefined) {
        const newDelay = parseInt(video_delay_ms);
        if (!isNaN(newDelay) && newDelay >= 0 && newDelay <= 10000) {
            VIDEO_DELAY_MS = newDelay;
            console.log(`Video delay updated to ${VIDEO_DELAY_MS}ms`);
            response.video_delay_ms = VIDEO_DELAY_MS;
            updated = true;
        } else {
            return res.status(400).json({
                status: 'error',
                message: 'Invalid video_delay_ms value (must be 0-10000)'
            });
        }
    }

    if (send_frequency_hz !== undefined) {
        const newFreq = parseInt(send_frequency_hz);
        if (!isNaN(newFreq) && newFreq >= 1 && newFreq <= 50) {
            SEND_FREQUENCY_HZ = newFreq;
            console.log(`Send frequency updated to ${SEND_FREQUENCY_HZ}Hz (${Math.round(1000/SEND_FREQUENCY_HZ)}ms interval)`);
            response.send_frequency_hz = SEND_FREQUENCY_HZ;
            response.send_interval_ms = Math.round(1000 / SEND_FREQUENCY_HZ);
            updated = true;
        } else {
            return res.status(400).json({
                status: 'error',
                message: 'Invalid send_frequency_hz value (must be 1-50)'
            });
        }
    }

    if (max_g_reset_interval_min !== undefined) {
        const newInterval = parseInt(max_g_reset_interval_min);
        if (!isNaN(newInterval) && newInterval >= 1 && newInterval <= 10) {
            MAX_G_RESET_INTERVAL_MIN = newInterval;
            console.log(`Max G reset interval updated to ${MAX_G_RESET_INTERVAL_MIN} minutes`);
            response.max_g_reset_interval_min = MAX_G_RESET_INTERVAL_MIN;
            updated = true;
        } else {
            return res.status(400).json({
                status: 'error',
                message: 'Invalid max_g_reset_interval_min value (must be 1-10)'
            });
        }
    }

    if (updated) {
        // Broadcast config change to all connected overlays
        broadcastConfig();
        res.json(response);
    } else {
        res.status(400).json({
            status: 'error',
            message: 'No valid parameters provided'
        });
    }
});

// GET /rtmp/validate - Validate RTMP stream key (called by nginx on_publish)
app.get('/rtmp/validate', (req, res) => {
    const streamName = req.query.name || '';

    log('RTMP publish attempt:', streamName);

    // Extract the key from the stream name (format: live/[key])
    const streamKey = streamName.split('/').pop();

    if (streamKey === STREAM_KEY) {
        log('✓ Valid stream key - allowing publish');
        res.status(200).send('OK');
    } else {
        console.log('✗ Invalid stream key - denying publish:', streamKey);
        res.status(403).send('Forbidden');
    }
});

// POST /rtmp/stream-event - Receive RTMP stream events from nginx
app.post('/rtmp/stream-event', (req, res) => {
    const { event, timestamp_ms, stream_name } = req.body;

    if (event === 'start') {
        console.log(`📹 RTMP stream started at ${timestamp_ms}ms (${stream_name})`);

        // Broadcast stream start to all clients
        broadcastMessage({
            type: 'rtmp-stream-start',
            timestamp_ms: timestamp_ms,
            stream_name: stream_name
        });

        // If there's an active session, update it with RTMP start time
        if (currentSession) {
            currentSession.rtmp_stream_start_ms = timestamp_ms;
            log('Updated session with RTMP start time');
        }
    } else if (event === 'stop') {
        console.log(`📹 RTMP stream stopped at ${timestamp_ms}ms`);

        // Broadcast stream stop to all clients
        broadcastMessage({
            type: 'rtmp-stream-stop',
            timestamp_ms: timestamp_ms
        });
    }

    res.status(200).send('OK');
});

// GET /rtmp/sync - Query RTMP stream info from nginx
app.get('/rtmp/sync', async (req, res) => {
    try {
        log('Querying nginx for RTMP stream info');

        // Query nginx on localhost:8080 (same container)
        const response = await fetch('http://localhost:8080/stream/info', {
            timeout: 1000
        });

        if (!response.ok) {
            throw new Error(`Nginx returned ${response.status}`);
        }

        const rtmpInfo = await response.json();
        log('RTMP info from nginx:', rtmpInfo);

        // Calculate telemetry delay if stream is live
        let telemetry_delay_ms = VIDEO_DELAY_MS;
        if (rtmpInfo.is_live && currentSession) {
            const sessionStart = new Date(currentSession.startTime).getTime();
            const streamStart = rtmpInfo.stream_start_time_unix_ms;
            telemetry_delay_ms = sessionStart - streamStart;
            log('Calculated telemetry delay:', telemetry_delay_ms, 'ms');
        }

        res.json({
            rtmp: rtmpInfo,
            telemetry_delay_ms,
            sync_available: rtmpInfo.is_live,
            video_delay_ms: VIDEO_DELAY_MS
        });

    } catch (error) {
        log('Error querying nginx:', error.message);

        // Return fallback response - sync not available
        res.json({
            rtmp: {
                is_live: false,
                stream_start_time_unix_ms: 0,
                error: error.message
            },
            sync_available: false,
            video_delay_ms: VIDEO_DELAY_MS
        });
    }
});

// GET /session - Current session info
app.get('/session', (req, res) => {
    if (currentSession) {
        log('Active session:', currentSession.id, 'with', sessionData.length, 'data points');
        res.json({
            ...currentSession,
            dataPoints: sessionData.length,
            latestData: latestTelemetry
        });
    } else {
        log('No active session');
        res.json({
            status: 'no active session'
        });
    }
});

// POST /session/end - Manually end current session
app.post('/session/end', (req, res) => {
    if (currentSession) {
        const sessionId = currentSession.id;
        log('Manually ending session:', sessionId);
        endSession();
        res.json({
            status: 'ok',
            message: 'Session ended',
            sessionId
        });
    } else {
        log('Cannot end session - no active session');
        res.json({
            status: 'error',
            message: 'No active session'
        });
    }
});

// POST /max-gforce/reset - Reset max g-force tracking
app.post('/max-gforce/reset', (req, res) => {
    log('Broadcasting max g-force reset to all clients');
    // Broadcast reset message to all clients
    broadcastMessage({ type: 'reset-max-gforce' });
    res.json({
        status: 'ok',
        message: 'Max g-force reset broadcast to all clients'
    });
});

// GET /archive - List archived sessions
app.get('/archive', (req, res) => {
    const archiveDir = path.join(__dirname, 'archive');

    if (!fs.existsSync(archiveDir)) {
        return res.json({ sessions: [] });
    }

    const sessions = [];

    function scanDirectory(dir, relativePath = '') {
        const items = fs.readdirSync(dir);

        for (const item of items) {
            const fullPath = path.join(dir, item);
            const stat = fs.statSync(fullPath);

            if (stat.isDirectory()) {
                scanDirectory(fullPath, path.join(relativePath, item));
            } else if (item.endsWith('.json')) {
                const content = JSON.parse(fs.readFileSync(fullPath, 'utf8'));
                sessions.push({
                    filename: item,
                    path: path.join(relativePath, item),
                    session: content.session,
                    dataPoints: content.data.length
                });
            }
        }
    }

    scanDirectory(archiveDir);

    // Sort by start time, newest first
    sessions.sort((a, b) =>
        new Date(b.session.startTime) - new Date(a.session.startTime)
    );

    res.json({ sessions });
});

// GET /archive/:year/:month/:filename - Get specific archived session
app.get('/archive/:year/:month/:filename', (req, res) => {
    const { year, month, filename } = req.params;
    const filepath = path.join(__dirname, 'archive', year, month, filename);

    if (fs.existsSync(filepath)) {
        const data = JSON.parse(fs.readFileSync(filepath, 'utf8'));
        res.json(data);
    } else {
        res.status(404).json({ error: 'Session not found' });
    }
});

// GET /health - Health check
app.get('/health', (req, res) => {
    res.json({
        status: 'ok',
        uptime: process.uptime(),
        session: currentSession ? currentSession.id : null
    });
});

// Serve admin page
app.get('/admin', (req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'admin.html'));
});

// Serve overlay at root
app.get('/', (req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

// 404 handler
app.use((req, res) => {
    res.status(404).json({ error: 'Not found' });
});

// Start server
server.listen(PORT, '0.0.0.0', () => {
    console.log('='.repeat(60));
    console.log('RaceBox Telemetry Server');
    console.log('='.repeat(60));
    console.log(`Port: ${PORT}`);
    console.log(`Debug mode: ${DEBUG ? 'ENABLED' : 'DISABLED'}`);
    console.log(`Session timeout: ${SESSION_TIMEOUT / 1000}s`);
    console.log(`WebSocket: ENABLED`);
    console.log(`Video delay: ${VIDEO_DELAY_MS}ms`);
    console.log(`Stream key: ${STREAM_KEY}`);
    console.log('');
    console.log(`Overlay URL: http://localhost:${PORT}`);
    console.log(`Admin page: http://localhost:${PORT}/admin`);
    console.log(`API endpoint: http://localhost:${PORT}/telemetry`);
    console.log(`WebSocket: ws://localhost:${PORT}`);
    console.log(`RTMP publish: rtmp://localhost:1935/live/${STREAM_KEY}`);
    console.log('='.repeat(60));
    if (DEBUG) {
        console.log('[DEBUG] Detailed logging enabled');
    }
});

// Graceful shutdown
process.on('SIGTERM', () => {
    console.log('SIGTERM received, ending session and shutting down...');
    endSession();
    process.exit(0);
});

process.on('SIGINT', () => {
    console.log('SIGINT received, ending session and shutting down...');
    endSession();
    process.exit(0);
});
