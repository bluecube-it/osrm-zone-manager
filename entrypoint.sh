#!/bin/bash
set -e

# osrm-zone-manager entrypoint
# Boot order:
#   1. ensure /data layout
#   2. ensure base PBF exists (download if missing — delegated to a util)
#   3. start embedded redis (persistence on /data/redis)
#   4. start uvicorn (orchestrator: FastAPI on :8080)
#   uvicorn handles boot recovery + evictor worker + builder via app startup hooks

DATA_DIR="${DATA_DIR:-/data}"
BASE_PBF="${BASE_PBF:-${DATA_DIR}/base/italy.osm.pbf}"
GEOFABRIK_URL="${GEOFABRIK_URL:-https://download.geofabrik.de/europe/italy-latest.osm.pbf}"
REDIS_DIR="${DATA_DIR}/redis"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"
ZONE_TTL_DAYS="${ZONE_TTL_DAYS:-90}"
MAX_ACTIVE_ZONES="${MAX_ACTIVE_ZONES:-20}"
OSRM_DEFAULT_RADIUS="${OSRM_DEFAULT_RADIUS:-50}"
UVICORN_WORKERS="${UVICORN_WORKERS:-1}"
LOG_LEVEL="${LOG_LEVEL:-info}"

mkdir -p "${DATA_DIR}/base" "${DATA_DIR}/zones" "${REDIS_DIR}"
ulimit -n 65536 2>/dev/null || true

echo "=== osrm-zone-manager boot ==="
echo "DATA_DIR=${DATA_DIR}"
echo "BASE_PBF=${BASE_PBF}"
echo "ZONE_TTL_DAYS=${ZONE_TTL_DAYS}"
echo "MAX_ACTIVE_ZONES=${MAX_ACTIVE_ZONES}"
echo "OSRM_DEFAULT_RADIUS=${OSRM_DEFAULT_RADIUS}"

# --- 1. Base PBF (download delegated to util on first need; here just warn) ---
if [ ! -f "${BASE_PBF}" ]; then
    echo "WARN: base PBF not found at ${BASE_PBF}."
    echo "      Set GEOFABRIK_URL and the orchestrator will fetch it on first /zones POST."
    echo "      Or mount it manually."
fi

# --- 2. Start embedded redis ---
echo "=== starting redis on ${REDIS_HOST}:${REDIS_PORT} (persistence: ${REDIS_DIR}) ==="
redis-server \
    --bind "${REDIS_HOST}" \
    --port "${REDIS_PORT}" \
    --dir "${REDIS_DIR}" \
    --save 60 10 \
    --appendonly yes \
    --appendfsync everysec \
    --daemonize yes \
    --loglevel notice

# Wait for redis
for i in $(seq 1 30); do
    if redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" ping 2>/dev/null | grep -q PONG; then
        echo "redis ready"
        break
    fi
    sleep 0.5
done

# sanity check
if ! redis-cli -h "${REDIS_HOST}" -p "${REDIS_PORT}" ping | grep -q PONG; then
    echo "FATAL: redis did not start"
    exit 1
fi

# --- 3. Start uvicorn (orchestrator) in foreground ---
# Boot recovery (Redis zones → restart sub-processes) runs in FastAPI startup hook.
# Evictor worker runs as asyncio task inside the same process.
echo "=== starting orchestrator (uvicorn) on :8080 ==="
export DATA_DIR BASE_PBF GEOFABRIK_URL REDIS_HOST REDIS_PORT
export ZONE_TTL_DAYS MAX_ACTIVE_ZONES OSRM_DEFAULT_RADIUS

exec uvicorn app.main:app \
    --host 0.0.0.0 \
    --port 8080 \
    --workers "${UVICORN_WORKERS}" \
    --log-level "${LOG_LEVEL}"
