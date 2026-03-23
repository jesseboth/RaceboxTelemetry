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
1. **Phase A (Structure)**
   - Add `/obs-live` page and route.
   - Render existing telemetry overlay in this page.
   - Keep existing ingest and auth.
2. **Phase B (Low-latency video egress)**
   - Add WebRTC (preferred) or MSE path for browser playback.
   - Wire stream key selection in page URL.
3. **Phase C (Sync engine)**
   - Implement baseline offset + drift correction logic in page.
   - Add `/sync/debug` endpoint.
4. **Phase D (Validation)**
   - Run repeated publish stop/start tests.
   - Compare overlay and video alignment over long sessions.
   - Tune smoothing thresholds and reconnect behavior.

## Acceptance Criteria
- Repeated publish stop/start keeps playback and telemetry sync stable.
- No large jumps in overlay values after reconnect.
- Drift remains bounded under normal network variation.
- OBS operator uses a single Browser Source URL for production.

## Notes
- RTMP ingest remains valid and simple for phone apps.
- Browser-based consume gives more deterministic control over timing logic than OBS consuming raw RTMP directly.
- For strictest latency and timing control, WebRTC egress is preferred over MSE/HLS.
