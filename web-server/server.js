const express = require('express');
const cors = require('cors');
const path = require('path');
const fs = require('fs');
const { v4: uuidv4 } = require('uuid');
const WebSocket = require('ws');
const http = require('http');

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

const PORT = process.env.PORT || 5000;
const DEBUG = process.env.DEBUG === 'true';
let VIDEO_DELAY_MS = parseInt(process.env.VIDEO_DELAY_MS) || 1500;

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
        data: { video_delay_ms: VIDEO_DELAY_MS }
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
function startSession() {
    if (!currentSession) {
        currentSession = {
            id: uuidv4(),
            startTime: new Date().toISOString(),
            dataPoints: 0
        };
        sessionData = [];
        console.log('=== NEW SESSION STARTED ===');
        log('Session ID:', currentSession.id);
        log('Start time:', currentSession.startTime);
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
    currentSession.dataPoints++;
    sessionData.push({
        ...data,
        _received: new Date().toISOString()
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

// GET /config - Configuration for overlay
app.get('/config', (req, res) => {
    log('Config requested, session:', currentSession);
    res.json({
        video_delay_ms: VIDEO_DELAY_MS,
        update_interval_ms: 100,
        session: currentSession
    });
});

// POST /config - Update configuration
app.post('/config', (req, res) => {
    const { video_delay_ms } = req.body;

    if (video_delay_ms !== undefined) {
        const newDelay = parseInt(video_delay_ms);
        if (!isNaN(newDelay) && newDelay >= 0 && newDelay <= 10000) {
            VIDEO_DELAY_MS = newDelay;
            console.log(`Video delay updated to ${VIDEO_DELAY_MS}ms`);

            // Broadcast config change to all connected overlays
            broadcastConfig();

            res.json({
                status: 'ok',
                video_delay_ms: VIDEO_DELAY_MS
            });
        } else {
            res.status(400).json({
                status: 'error',
                message: 'Invalid video_delay_ms value (must be 0-10000)'
            });
        }
    } else {
        res.status(400).json({
            status: 'error',
            message: 'Missing video_delay_ms parameter'
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
    console.log('');
    console.log(`Overlay URL: http://localhost:${PORT}`);
    console.log(`Admin page: http://localhost:${PORT}/admin`);
    console.log(`API endpoint: http://localhost:${PORT}/telemetry`);
    console.log(`WebSocket: ws://localhost:${PORT}`);
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
