FROM node:latest

ENV TZ=America/New_York
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# Install build dependencies for nginx
RUN apt-get update && apt-get install -y \
    build-essential \
    libpcre3 \
    libpcre3-dev \
    libssl-dev \
    zlib1g-dev \
    libluajit2-5.1-dev \
    lua-resty-core \
    lua-resty-lrucache \
    supervisor \
    git \
    wget \
    && rm -rf /var/lib/apt/lists/*

# Build nginx with RTMP and Lua modules
WORKDIR /tmp
RUN wget http://nginx.org/download/nginx-1.24.0.tar.gz && \
    tar -zxvf nginx-1.24.0.tar.gz && \
    git clone https://github.com/arut/nginx-rtmp-module.git && \
    cd nginx-1.24.0 && \
    ./configure \
        --prefix=/usr/local/nginx \
        --with-http_ssl_module \
        --with-http_v2_module \
        --with-http_realip_module \
        --with-http_addition_module \
        --with-http_sub_module \
        --with-http_dav_module \
        --with-http_flv_module \
        --with-http_mp4_module \
        --with-http_gunzip_module \
        --with-http_gzip_static_module \
        --with-http_random_index_module \
        --with-http_secure_link_module \
        --with-http_stub_status_module \
        --with-http_auth_request_module \
        --with-threads \
        --with-stream \
        --with-stream_ssl_module \
        --with-stream_realip_module \
        --with-ld-opt="-Wl,-rpath,/usr/lib/x86_64-linux-gnu/" \
        --add-module=../nginx-rtmp-module && \
    make -j$(nproc) && \
    make install && \
    cd / && \
    rm -rf /tmp/*

# Create necessary directories
RUN mkdir -p /var/log/nginx /var/log/supervisor /app/html

# Set up Node.js application
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .

# Copy nginx configuration
COPY nginx.conf /usr/local/nginx/conf/nginx.conf

# Copy supervisord configuration
COPY supervisord.conf /etc/supervisor/conf.d/supervisord.conf

# Copy HTML files (stats page)
COPY html/ /app/html/

# Create rtmp-push.conf from example if it doesn't exist
RUN if [ ! -f rtmp-push.conf ]; then \
        echo "# Auto-generated placeholder - edit with your OBS IP" > /app/rtmp-push.conf && \
        echo "# Example: push rtmp://192.168.1.100:1935/listen;" >> /app/rtmp-push.conf && \
        echo "" >> /app/rtmp-push.conf; \
    fi

# Expose ports
EXPOSE 5000 1935 8080

# Run supervisord to manage both nginx and Node.js
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
