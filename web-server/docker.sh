#!/bin/bash

# Docker management script for RaceBox Telemetry Server
# Usage: ./docker.sh [daemon|stop|restart|start|log|smoke-rtmp] [options]

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_NAME="racebox-telemetry-server"
CONTAINER_NAME="racebox-telemetry"
DEFAULT_PORT=5000
PORT=$DEFAULT_PORT
NETWORK=""
DEBUG_FLAG=""
VIDEO_DELAY=""
STREAM_KEY=""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -p|--port)
            PORT="$2"
            shift 2
            ;;
        --network)
            NETWORK="$2"
            shift 2
            ;;
        --debug)
            DEBUG_FLAG="-e DEBUG=true"
            shift
            ;;
        --delay)
            VIDEO_DELAY="-e VIDEO_DELAY_MS=$2"
            shift 2
            ;;
        --stream-key)
            STREAM_KEY="-e STREAM_KEY=$2"
            shift 2
            ;;
        daemon|stop|restart|start|log|build|smoke-rtmp)
            if [ -z "${COMMAND:-}" ]; then
                COMMAND="$1"
            elif { [ "$COMMAND" = "restart" ] && { [ "$1" = "daemon" ] || [ "$1" = "start" ]; }; } || \
                 { { [ "$COMMAND" = "daemon" ] || [ "$COMMAND" = "start" ]; } && [ "$1" = "restart" ]; }; then
                # Allow common alias forms like: ./docker.sh restart daemon
                COMMAND="restart"
            elif [ "$COMMAND" = "$1" ]; then
                # Ignore duplicate same command token
                :
            else
                echo "Error: Multiple conflicting commands: $COMMAND and $1"
                echo "Use one command: daemon|start|stop|restart|log|build|smoke-rtmp"
                exit 1
            fi
            shift
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Set default command if none provided
COMMAND=${COMMAND:-daemon}

# Function to print colored messages
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to check if container is running
is_running() {
    docker ps -q -f name=$CONTAINER_NAME
}

# Function to check if container exists
container_exists() {
    docker ps -a -q -f name=$CONTAINER_NAME
}

# Function to build the Docker image
build_image() {
    print_info "Building Docker image: $IMAGE_NAME"
    cd "$SCRIPT_DIR" || exit 1

    docker build -t $IMAGE_NAME .

    if [ $? -eq 0 ]; then
        print_success "Docker image built successfully"
    else
        print_error "Failed to build Docker image"
        exit 1
    fi
}

# Function to stop container
stop_container() {
    if [ "$(is_running)" ]; then
        print_info "Stopping container: $CONTAINER_NAME"
        docker stop $CONTAINER_NAME
        print_success "Container stopped"
    else
        print_warning "Container is not running"
    fi
}

# Function to remove container
remove_container() {
    if [ "$(container_exists)" ]; then
        print_info "Removing container: $CONTAINER_NAME"
        docker rm $CONTAINER_NAME
        print_success "Container removed"
    fi
}

# Function to start container
start_container() {
    if [ "$(is_running)" ]; then
        print_warning "Container is already running"
        exit 0
    fi

    # Build image if it doesn't exist
    if [ -z "$(docker images -q $IMAGE_NAME)" ]; then
        build_image
    fi

    # Remove existing container if it exists
    if [ "$(container_exists)" ]; then
        remove_container
    fi

    print_info "Starting container: $CONTAINER_NAME"
    print_info "Port mapping: Web $PORT:5000, RTMP 1935:1935, RTSP 8554:8554"

    # Build docker run command
    DOCKER_CMD="docker run -d --name $CONTAINER_NAME"

    # Add network if specified
    if [ -n "$NETWORK" ]; then
        DOCKER_CMD="$DOCKER_CMD --network $NETWORK"
        print_info "Using network: $NETWORK"
    else
        DOCKER_CMD="$DOCKER_CMD -p $PORT:5000 -p 1935:1935 -p 8554:8554"
    fi

    # Add debug flag if specified
    if [ -n "$DEBUG_FLAG" ]; then
        DOCKER_CMD="$DOCKER_CMD $DEBUG_FLAG"
        print_info "Debug mode enabled"
    fi

    # Add video delay if specified
    if [ -n "$VIDEO_DELAY" ]; then
        DOCKER_CMD="$DOCKER_CMD $VIDEO_DELAY"
        DELAY_VALUE=$(echo $VIDEO_DELAY | sed 's/-e VIDEO_DELAY_MS=//')
        print_info "Video delay set to: ${DELAY_VALUE}ms"
    fi

    # Add stream key if specified
    if [ -n "$STREAM_KEY" ]; then
        DOCKER_CMD="$DOCKER_CMD $STREAM_KEY"
        KEY_VALUE=$(echo $STREAM_KEY | sed 's/.*STREAM_KEY=//')
        print_info "Stream key: ${KEY_VALUE}"
    else
        print_warning "Using default stream key (change with --stream-key for security!)"
    fi

    # Add volume mounts
    DOCKER_CMD="$DOCKER_CMD -v ${SCRIPT_DIR}/archive:/app/archive"

    # Add restart policy and image name
    DOCKER_CMD="$DOCKER_CMD --restart always $IMAGE_NAME"

    # Execute the command
    eval $DOCKER_CMD

    if [ $? -eq 0 ]; then
        print_success "Container started successfully"
        print_info "Overlay:         http://localhost:$PORT"
        print_info "RTMP publish:    rtmp://localhost:1935/live/<stream-key>"
        print_info "RTSP pull (OBS): rtsp://localhost:8554/live/<stream-key>"
        print_info "RTMP pull (alt): rtmp://localhost:1935/live/<stream-key>"
        print_info "View logs with: ./docker.sh log"
    else
        print_error "Failed to start container"
        exit 1
    fi
}

# Function to view logs
view_logs() {
    if [ "$(container_exists)" ]; then
        print_info "Viewing logs for: $CONTAINER_NAME"
        docker logs -f $CONTAINER_NAME
    else
        print_error "Container does not exist"
        exit 1
    fi
}

extract_stream_key() {
    if [ -n "$STREAM_KEY" ]; then
        echo "$STREAM_KEY" | sed 's/.*STREAM_KEY=//'
        return
    fi

    if [ "$(container_exists)" ]; then
        docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$CONTAINER_NAME" 2>/dev/null \
            | sed -n 's/^STREAM_KEY=//p' \
            | head -n 1
        return
    fi

    echo ""
}

run_with_timeout() {
    local timeout_s="$1"
    shift

    "$@" &
    local cmd_pid=$!

    (
        sleep "$timeout_s"
        if kill -0 "$cmd_pid" 2>/dev/null; then
            kill "$cmd_pid" 2>/dev/null
        fi
    ) &
    local watchdog_pid=$!

    wait "$cmd_pid"
    local cmd_rc=$?

    kill "$watchdog_pid" 2>/dev/null
    wait "$watchdog_pid" 2>/dev/null

    return "$cmd_rc"
}

smoke_rtmp() {
    if [ ! "$(is_running)" ]; then
        print_error "Container is not running. Start it first with: ./docker.sh --stream-key <key> daemon"
        exit 1
    fi

    if ! command -v ffmpeg >/dev/null 2>&1; then
        print_error "ffmpeg not found on host. Install ffmpeg to run smoke-rtmp."
        exit 1
    fi

    local key
    key="$(extract_stream_key)"
    if [ -z "$key" ]; then
        key="racebox-default-key"
    fi

    local publish_url="rtmp://127.0.0.1:1935/live/$key"
    local pull_url="rtsp://127.0.0.1:8554/live/$key"
    local publisher_log="/tmp/racebox-smoke-publisher.log"
    local listener_log="/tmp/racebox-smoke-listener.log"

    rm -f "$publisher_log" "$listener_log"

    print_info "Running RTMP smoke test with stream key: $key"
    print_info "Publishing to:  $publish_url"
    print_info "Pulling from:   $pull_url"

    # Start publisher
    ffmpeg -hide_banner -loglevel error -re \
        -f lavfi -i testsrc=size=640x360:rate=30 \
        -f lavfi -i sine=frequency=1000 \
        -c:v libx264 -pix_fmt yuv420p -profile:v baseline -level 3.1 \
        -preset veryfast -tune zerolatency -g 30 -keyint_min 30 -sc_threshold 0 \
        -c:a aac -ar 44100 -ac 1 -b:a 96k \
        -f flv "$publish_url" >"$publisher_log" 2>&1 &
    local publisher_pid=$!

    sleep 3
    if ! kill -0 "$publisher_pid" 2>/dev/null; then
        print_error "Publisher exited early."
        tail -n 60 "$publisher_log" 2>/dev/null
        exit 1
    fi

    # Verify MediaMTX sees the publisher via its REST API
    local path_json
    path_json="$(docker exec "$CONTAINER_NAME" sh -c \
        "wget -qO- http://127.0.0.1:9997/v3/paths/list" 2>/dev/null)"
    local has_publisher
    has_publisher="$(echo "$path_json" | grep -c '"sourceType"' || true)"

    # Pull stream via RTSP to confirm media flows end-to-end
    run_with_timeout 20 ffmpeg -hide_banner -loglevel info -t 6 -i "$pull_url" -f null - >"$listener_log" 2>&1
    local listen_rc=$?

    if kill -0 "$publisher_pid" 2>/dev/null; then
        kill "$publisher_pid" 2>/dev/null
        wait "$publisher_pid" 2>/dev/null
    fi

    if grep -Eq "video:[[:space:]]*[1-9]" "$listener_log" && grep -Eq "audio:[[:space:]]*[1-9]" "$listener_log"; then
        print_success "Smoke test passed: RTMP publish → RTSP pull carrying video and audio."
        return
    fi

    if [ "${has_publisher:-0}" -gt 0 ] 2>/dev/null; then
        print_success "Smoke test passed: MediaMTX reports an active publisher (RTSP pull incomplete but stream is live)."
        return
    fi

    print_error "Smoke test failed."
    echo ""
    print_info "Listener log tail:"
    tail -n 80 "$listener_log" 2>/dev/null
    echo ""
    print_info "Publisher log tail:"
    tail -n 80 "$publisher_log" 2>/dev/null
    exit 1
}

# Main command execution
case $COMMAND in
    build)
        build_image
        ;;
    daemon|start)
        start_container
        ;;
    stop)
        stop_container
        ;;
    restart)
        stop_container
        sleep 2
        start_container
        ;;
    log)
        view_logs
        ;;
    smoke-rtmp)
        smoke_rtmp
        ;;
    *)
        print_error "Unknown command: $COMMAND"
        echo "Usage: $0 [daemon|stop|restart|start|log|build|smoke-rtmp] [options]"
        echo ""
        echo "Commands:"
        echo "  daemon|start  - Start container as daemon"
        echo "  stop          - Stop container"
        echo "  restart       - Restart container"
        echo "  log           - View container logs"
        echo "  build         - Build Docker image"
        echo "  smoke-rtmp    - Run end-to-end RTMP publish/listen smoke test"
        echo ""
        echo "Options:"
        echo "  -p|--port PORT       - Override web server port (default: $DEFAULT_PORT)"
        echo "  --network NETWORK    - Use specific Docker network (enables host networking)"
        echo "  --debug              - Enable debug mode"
        echo "  --delay MILLISECONDS - Video delay in ms"
        echo "  --stream-key KEY     - Set stream key for authentication (default: racebox-default-key)"
        echo ""
        echo "Ports:"
        echo "  5000 - Web server (overlay + telemetry API + WebSocket)"
        echo "  1935 - RTMP (phone publishes here)"
        echo "  8554 - RTSP (OBS pulls from here — preferred)"
        echo "  9997 - MediaMTX REST API (internal)"
        echo ""
        echo "Stream Setup:"
        echo "  Phone publishes to: rtmp://server-ip:1935/live/<stream-key>"
        echo "  OBS pulls from:     rtsp://server-ip:8554/live/<stream-key>"
        echo "  OBS alt (RTMP):     rtmp://server-ip:1935/live/<stream-key>"
        echo ""
        echo "Examples:"
        echo "  $0 daemon                              # Start on default port $DEFAULT_PORT"
        echo "  $0 -p 8080 daemon                      # Start with web server on port 8080"
        echo "  $0 --debug daemon                      # Start with debug logging"
        echo "  $0 --stream-key mySecretKey123 daemon  # Start with custom stream key"
        echo "  $0 --network host daemon               # Start with host networking"
        echo "  $0 log                                 # View logs"
        echo "  $0 smoke-rtmp                          # Verify RTMP publish → RTSP pull"
        exit 1
        ;;
esac
