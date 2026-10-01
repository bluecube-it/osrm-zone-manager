ARG DEBIAN_VERSION=13
ARG VROOM_VERSION=v1.15.0
ARG OSRM_VERSION=v26.10.0-debian
ARG OSMIUM_VERSION=1.19.0

# ---- Stage 0: Maven build ----
FROM --platform=linux/amd64 maven:3.9-eclipse-temurin-25 AS maven_builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests -q

# ---- Stage 1: OSRM backend binaries + lua profiles (debian variant: OSRM >= 26.7 has no alpine build) ----
FROM --platform=linux/amd64 ghcr.io/project-osrm/osrm-backend:${OSRM_VERSION} AS osrm_builder

# ---- Stage 2: vroom binary (C++) ----
FROM --platform=linux/amd64 debian:${DEBIAN_VERSION}-slim AS vroom_builder
ARG VROOM_VERSION
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates \
        git \
        make \
        cmake \
        g++ \
        pkg-config \
        libasio-dev \
        libglpk-dev \
        libssl-dev && \
    rm -rf /var/lib/apt/lists/* && \
    git clone --branch ${VROOM_VERSION} --single-branch --recurse-submodules https://github.com/VROOM-Project/vroom.git && \
    make -C /vroom/src -j"$(nproc)"

# ---- Stage 3: osmium-tool (osmium extract / merge) ----
# Built from source so the version stays pinned; the resulting binary is static.
FROM --platform=linux/amd64 debian:${DEBIAN_VERSION}-slim AS osmium_builder
ARG OSMIUM_VERSION
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates \
        git \
        make \
        cmake \
        g++ \
        pkg-config \
        libosmium2-dev \
        libprotozero-dev \
        libboost-program-options-dev \
        nlohmann-json3-dev \
        libbz2-dev \
        libexpat1-dev \
        zlib1g-dev \
        liblz4-dev && \
    rm -rf /var/lib/apt/lists/* && \
    git clone --depth 1 --branch v${OSMIUM_VERSION} https://github.com/osmcode/osmium-tool.git /osmium-tool && \
    cmake -S /osmium-tool -B /osmium-build -DCMAKE_BUILD_TYPE=Release && \
    cmake --build /osmium-build -j"$(nproc)" && \
    cmake --install /osmium-build --prefix /usr/local

# ---- Stage 4: runtime ----
FROM --platform=linux/amd64 debian:${DEBIAN_VERSION}-slim AS runstage

ARG OSRM_VERSION
ARG VROOM_VERSION

ENV DEBIAN_FRONTEND=noninteractive

# Runtime packages: Java, python (reduce.py) and the shared libraries the three binaries need
# (osrm-routed: libc/libstdc++ only; vroom: openssl + glpk; osmium: boost-program-options, zlib,
# expat, bz2, lz4, xxhash).
# build-essential/python3-dev/pybind11-dev/libosmium2-dev/libprotozero-dev/libboost-dev and the
# *-dev compression headers are needed to compile the pyosmium bindings and are purged right after.
RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-25-jre-headless \
        python3 \
        python3-pip \
        python3-shapely \
        libssl3t64 \
        libglpk40 \
        libboost-program-options1.83.0 \
        libxxhash0 \
        libexpat1 \
        libbz2-1.0 \
        zlib1g \
        liblz4-1 \
        curl \
        bash \
        file \
        procps \
        build-essential \
        python3-dev \
        pybind11-dev \
        libosmium2-dev \
        libprotozero-dev \
        libboost-dev \
        libbz2-dev \
        libexpat1-dev \
        zlib1g-dev \
        liblz4-dev && \
    pip3 install --no-cache-dir --break-system-packages osmium && \
    apt-get purge -y --auto-remove \
        build-essential \
        python3-dev \
        pybind11-dev \
        libosmium2-dev \
        libprotozero-dev \
        libboost-dev \
        libbz2-dev \
        libexpat1-dev \
        zlib1g-dev \
        liblz4-dev && \
    rm -rf /var/lib/apt/lists/* /root/.cache

# Copy OSRM binaries + lua profiles (car.lua / bus.lua live in /opt)
COPY --from=osrm_builder /usr/local/bin/. /usr/local/bin
COPY --from=osrm_builder /opt/. /opt

# Copy vroom binary (invoked per VROOM request by the gateway)
COPY --from=vroom_builder /vroom/bin/vroom /usr/local/bin

# Copy osmium-tool binary (installed by cmake --install in the builder stage)
COPY --from=osmium_builder /usr/local/bin/osmium /usr/local/bin/osmium

# Copy application
COPY --from=maven_builder /build/target/application.jar /app/application.jar
COPY src/main/scripts/reduce.py /app/scripts/reduce.py
# Lua routing profiles consumed by `osrm-extract -p`; bus.lua requires car.lua, so both live in /opt
COPY src/main/resources/config/bus.lua /opt/bus.lua
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /app/scripts/reduce.py /entrypoint.sh

WORKDIR /app

VOLUME ["/data", "/config"]

EXPOSE 8080

HEALTHCHECK --start-period=10m --interval=30s --timeout=3s --retries=5 \
    CMD curl --fail -s http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["/entrypoint.sh"]
