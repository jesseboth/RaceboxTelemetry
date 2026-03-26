# OBS Low-Latency Video + Telemetry Sync Plan

## Goal
Achieve near frame-accurate alignment between video and telemetry in OBS.

## Decision
- Keep RTMP ingest from phone/app into this server.
- Do **not** use HLS for OBS playback if tight sync is required.
- Serve OBS as a **Browser Source** where both video and telemetry are rendered in the same page and timed from the same clock logic.

## Why Not HLS
- HLS adds segment/buffer latency (typically higher and more variable).
- HLS timestamp control is coarser, which hurts tight telemetry-to-video alignment.

## Target Architecture
1. Phone/app publishes RTMP:
   - `rtmp://SERVER:1935/publish/<stream-key>`
2. Server keeps stream key authentication and stream lifecycle events.
3. Server provides low-latency browser-playable egress:
   - Preferred: WebRTC
   - Fallback: low-latency MSE/fMP4
4. OBS loads one Browser Source URL:
   - Example: `http://SERVER:5000/obs-live?key=<stream-key>`
5. `/obs-live` renders:
   - Live video
   - Telemetry overlay
   - Sync logic in one timeline

## Sync Model
Use server-time based alignment.

### Signals
- `video_start_server_ms`: server timestamp when publish starts (or first decodable video anchor).
- `telemetry_server_ms`: timestamp per telemetry sample, normalized to server time.
- `telemetry_start_server_ms`: first sample timestamp of active telemetry session.

### Baseline Offset
- `offset_ms = telemetry_start_server_ms - video_start_server_ms`
- Positive means telemetry started later than video.
- Negative means telemetry started earlier than video.

### Render Selection Rule
In the OBS browser page, choose telemetry sample where:
- `telemetry_server_ms <= (estimated_video_server_ms + offset_ms)`

`estimated_video_server_ms` is derived from media playback progression and anchor time.

## Drift Handling
- Measure drift continuously between expected media progression and telemetry timeline.
- Apply small corrections gradually (example: `±5-20ms` every second), avoid hard jumps.
- Recompute baseline after stream reconnects or discontinuities.

## Reconnect Behavior
- On video disconnect:
  - freeze telemetry or mark stale
  - wait for new stream anchor
- On new stream start:
  - update `video_start_server_ms`
  - recompute `offset_ms`
  - resume normal rendering

## Observability Requirements
Add a debug endpoint and logs for validation.

### Suggested Debug Endpoint
- `GET /sync/debug`
- Fields:
  - `video_start_server_ms`
  - `telemetry_start_server_ms`
  - `offset_ms`
  - `drift_ms`
  - `stream_live`
  - `session_id`
  - `last_update_age_ms`

### Logging
Log these events with timestamps:
- publish start/stop
- browser player connected/disconnected
- offset recalculated
- drift corrections applied

## Rollout Plan

### Phase A (Structure) — DONE
- [x] RTMP ingest with stream key auth (`/rtmp/on_publish`, `/rtmp/on_publish_done`)
- [x] `currentStreamStart_ms` tracked in Node memory on publish start
- [x] `session.startTime_unix_ms` tracked on first telemetry sample
- [x] `telemetry_delay_ms` (`offset_ms`) computed at stream/session start and broadcast via WebSocket
- [x] Stream lifecycle events (`rtmp-stream-start`, `rtmp-stream-stop`, `session-start`) broadcast to all WS clients
- [x] `/rtmp/sync` endpoint exposes live sync state (covers most of the planned `/sync/debug`)
- [x] Telemetry overlay (`/`) renders speed + g-force with delay/sync logic
- [x] `logSyncSnapshot()` logs publish start/stop and session events with timestamps

### Phase B (Low-latency video egress) — NOT STARTED
- [ ] Integrate mediasoup to bridge RTMP → WebRTC
  - This is the largest remaining task
  - mediasoup runs as a Node.js SFU inside the existing server process (or as a sidecar)
  - nginx-rtmp pushes the ingest stream to an ffmpeg process that feeds mediasoup via plain RTP
- [ ] Add `/obs-live` route and page
- [ ] Embed `<video>` element in `/obs-live`, connected to WebRTC (or MSE/fMP4 as fallback)
- [ ] Wire stream key selection via page URL (`?key=<stream-key>`)

### Phase C (Sync engine in browser) — PARTIALLY DONE
- [x] Phone clock offset detection (running average over 10 samples, applied per telemetry point)
- [x] Timestamp-based telemetry buffering: each point gets a `_displayTime` derived from phone timestamp + clock offset + video delay
- [x] Fallback to fixed-delay sync when RTMP is not live or clock offset not yet known
- [x] Buffer cleared and sync mode toggled on stream start/stop events
- [ ] Drift measurement against `video.currentTime` (blocked on Phase B — needs a `<video>` element)
- [ ] Gradual drift correction (±5–20ms per second) once video element exists
- [ ] `/sync/debug` endpoint with `drift_ms` field (current `/rtmp/sync` is missing this)

### Phase D (Validation) — NOT STARTED
- [ ] Run repeated publish stop/start tests
- [ ] Compare overlay and video alignment over long sessions
- [ ] Tune smoothing thresholds and reconnect behavior

## Acceptance Criteria
- Repeated publish stop/start keeps playback and telemetry sync stable.
- No large jumps in overlay values after reconnect.
- Drift remains bounded under normal network variation.
- OBS operator uses a single Browser Source URL for production.

## Notes
- RTMP ingest remains valid and simple for phone apps.
- Browser-based consume gives more deterministic control over timing logic than OBS consuming raw RTMP directly.
- For strictest latency and timing control, WebRTC egress is preferred over MSE/HLS.
- The telemetry sync engine (clock offset, buffering, reconnect handling) is essentially complete. The dominant remaining work is Phase B: getting a live video feed into the browser page.
