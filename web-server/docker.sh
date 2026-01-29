#!/bin/bash

# Docker management script for RaceBox Telemetry Server
# Usage: ./docker.sh [daemon|stop|restart|start|log] [options]

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
        daemon|stop|restart|start|log|build)
            COMMAND="$1"
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

    # Check if rtmp-push.conf exists
    if [ ! -f "${SCRIPT_DIR}/rtmp-push.conf" ]; then
        print_warning "rtmp-push.conf not found!"
        print_info "Creating rtmp-push.conf from example template..."
        print_info "Please edit rtmp-push.conf with your OBS machine IP address"
        print_info "Example: push rtmp://192.168.1.100:1935/live/stream;"
        if [ -f "${SCRIPT_DIR}/rtmp-push.conf.example" ]; then
            cp "${SCRIPT_DIR}/rtmp-push.conf.example" "${SCRIPT_DIR}/rtmp-push.conf"
        else
            echo "# RTMP Push Configuration - Edit with your OBS IP" > "${SCRIPT_DIR}/rtmp-push.conf"
            echo "# Example: push rtmp://192.168.1.100:1935/live/stream;" >> "${SCRIPT_DIR}/rtmp-push.conf"
        fi
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
    print_info "Port mapping: Web $PORT:5000, RTMP 1935:1935, Nginx Stats 5001:8080"

    # Build docker run command
    DOCKER_CMD="docker run -d --name $CONTAINER_NAME"

    # Add network if specified
    if [ -n "$NETWORK" ]; then
        DOCKER_CMD="$DOCKER_CMD --network $NETWORK"
        print_info "Using network: $NETWORK"
    else
        DOCKER_CMD="$DOCKER_CMD -p $PORT:5000 -p 1935:1935 -p 5001:8080"
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
        KEY_VALUE=$(echo $STREAM_KEY | sed 's/-e STREAM_KEY=//')
        print_info "Stream key set to: ${KEY_VALUE}"
    else
        print_warning "Using default stream key (change with --stream-key for security!)"
    fi

    # Add volume mounts
    DOCKER_CMD="$DOCKER_CMD -v ${SCRIPT_DIR}/archive:/usr/src/app/archive"

    # Add restart policy and image name
    DOCKER_CMD="$DOCKER_CMD --restart always $IMAGE_NAME"

    # Execute the command
    eval $DOCKER_CMD

    if [ $? -eq 0 ]; then
        print_success "Container started successfully"
        print_info "Access server at: http://localhost:$PORT"
        print_info "RTMP stream: rtmp://localhost:1935/live/stream"
        print_info "Nginx stats: http://localhost:5001/stat"
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
    *)
        print_error "Unknown command: $COMMAND"
        echo "Usage: $0 [daemon|stop|restart|start|log|build] [options]"
        echo ""
        echo "Commands:"
        echo "  daemon|start  - Start container as daemon"
        echo "  stop          - Stop container"
        echo "  restart       - Restart container"
        echo "  log           - View container logs"
        echo "  build         - Build Docker image"
        echo ""
        echo "Options:"
        echo "  -p|--port PORT       - Override web server port (default: $DEFAULT_PORT)"
        echo "  --network NETWORK    - Use specific Docker network (enables host networking)"
        echo "  --debug              - Enable debug mode"
        echo "  --delay MILLISECONDS - Video delay in ms for stream sync (default: 1500)"
        echo "  --stream-key KEY     - Set RTMP stream key for authentication (default: racebox-default-key)"
        echo ""
        echo "Ports:"
        echo "  5000 - Web server (telemetry and overlay)"
        echo "  1935 - RTMP server (stream input from phone)"
        echo "  5001 - Nginx stats and API"
        echo ""
        echo "RTMP Setup:"
        echo "  1. Copy rtmp-push.conf.example to rtmp-push.conf"
        echo "  2. Edit rtmp-push.conf with your OBS machine IP"
        echo "  3. Configure phone to stream to rtmp://server-ip:1935/live/stream"
        echo "  4. Configure OBS Media Source with same RTMP URL"
        echo ""
        echo "Examples:"
        echo "  $0 daemon                              # Start on default port $DEFAULT_PORT"
        echo "  $0 -p 8080 daemon                      # Start with web server on port 8080"
        echo "  $0 --debug daemon                      # Start with debug logging"
        echo "  $0 --delay 2000 daemon                 # Start with 2000ms video delay"
        echo "  $0 --stream-key mySecretKey123 daemon  # Start with custom stream key"
        echo "  $0 --network host daemon               # Start with host networking"
        echo "  $0 log                                 # View logs"
        exit 1
        ;;
esac
