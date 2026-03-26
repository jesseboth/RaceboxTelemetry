FROM node:latest

ENV TZ=America/New_York
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# Install runtime dependencies
RUN apt-get update && apt-get install -y \
    curl \
    supervisor \
    && rm -rf /var/lib/apt/lists/*

# Install MediaMTX (single binary — replaces nginx + nginx-rtmp-module)
# Check https://github.com/bluenviron/mediamtx/releases for the latest version
ARG MEDIAMTX_VERSION=1.9.3
RUN curl -fsSL \
    "https://github.com/bluenviron/mediamtx/releases/download/v${MEDIAMTX_VERSION}/mediamtx_v${MEDIAMTX_VERSION}_linux_amd64.tar.gz" \
    -o /tmp/mediamtx.tar.gz && \
    tar -xzf /tmp/mediamtx.tar.gz -C /usr/local/bin mediamtx && \
    rm /tmp/mediamtx.tar.gz

# Set up Node.js application
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .

# Copy supervisord configuration
COPY supervisord.conf /etc/supervisor/conf.d/supervisord.conf

# Expose ports:
#   5000 - Node.js (overlay + telemetry API + WebSocket)
#   1935 - RTMP  (phone streams in, OBS can pull)
#   8554 - RTSP  (OBS pulls stream from here — preferred)
#   9997 - MediaMTX REST API (internal)
EXPOSE 5000 1935 8554 9997

CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
