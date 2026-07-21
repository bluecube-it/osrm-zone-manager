ARG ALPINE_VERSION=3.23
ARG VROOM_VERSION=v1.15.0
ARG OSRM_VERSION=v26.4
ARG OSMIUM_VERSION=1.16.0

# ---- Stage 0: Maven build ----
FROM --platform=linux/amd64 maven:3-eclipse-temurin-26 AS maven_builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests -q

# ---- Stage 1: OSRM backend binaries + car.lua profile ----
FROM --platform=linux/amd64 ghcr.io/project-osrm/osrm-backend:${OSRM_VERSION} AS osrm_builder

# ---- Stage 2: vroom binary (C++) ----
FROM --platform=linux/amd64 alpine:${ALPINE_VERSION} AS vroom_builder
ARG VROOM_VERSION
RUN apk --update --no-cache add \
        asio-dev \
        build-base \
        cmake \
        git \
        glpk-dev \
        openssl-dev \
        pkgconf && \
    git clone --branch ${VROOM_VERSION} --single-branch --recurse-submodules https://github.com/VROOM-Project/vroom.git && \
    cd vroom && \
    make -C /vroom/src -j$(nproc)

# ---- Stage 3: vroom-express node app ----
FROM --platform=linux/amd64 ghcr.io/vroom-project/vroom-docker:${VROOM_VERSION} AS vroom_node_builder

# ---- Stage 4: osmium-tool base (for osmium extract/merge) ----
FROM --platform=linux/amd64 iboates/osmium:${OSMIUM_VERSION} AS osmium_builder

# ---- Stage 5: runtime ----
FROM --platform=linux/amd64 alpine:${ALPINE_VERSION} AS runstage

ARG OSRM_VERSION
ARG VROOM_VERSION

# Install runtime deps
RUN apk --update --no-cache add \
        openjdk25-jre \
        python3 \
        py3-pip \
        py3-setuptools \
        py3-wheel \
        build-base \
        python3-dev \
        boost-dev \
        expat-dev \
        cmake \
        bzip2-dev \
        zlib-dev \
        lua5.4-dev \
        lz4-dev \
        libtbb-dev \
        glpk-dev \
        nodejs \
        npm \
        curl \
        bash \
        file \
        procps-ng && \
    pip3 install --no-cache-dir --break-system-packages \
        osmium \
        shapely \
        polyline && \
    rm -rf /var/cache/apk/*

# Copy OSRM binaries + profiles
COPY --from=osrm_builder /usr/local/bin/. /usr/local/bin
COPY --from=osrm_builder /opt/. /opt

# Copy vroom binary
COPY --from=vroom_builder /vroom/bin/vroom /usr/local/bin

# Copy vroom-express node app
COPY --from=vroom_node_builder /vroom-express/. /vroom-express

# Copy osmium-tool binary
COPY --from=osmium_builder /usr/local/bin/osmium /usr/local/bin/osmium

# Copy application
COPY --from=maven_builder /build/target/application.jar /app/application.jar
COPY src/main/scripts/reduce.py /app/scripts/reduce.py
COPY src/main/resources/config/vroom-config.template.yml /app/config/vroom-config.template.yml
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /app/scripts/reduce.py /entrypoint.sh

WORKDIR /app

VOLUME ["/data", "/config"]

EXPOSE 8080

HEALTHCHECK --start-period=10m --interval=30s --timeout=3s --retries=5 \
    CMD curl --fail -s http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["/entrypoint.sh"]
