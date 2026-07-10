#!/bin/bash
set -e

# osrm-zone-manager entrypoint
# Boot order:
#   1. ensure /data (ephemeral) + /config (GCS FUSE) layout
#   2. start uvicorn (orchestrator: FastAPI on :8080)
#   uvicorn handles: PBF download + boot recovery (registry.json) + evictor + builder
#
# Storage layout:
#   /config  — GCS FUSE bucket (persistent) — registry.json only
#   /data    — ephemeral (emptyDir / tmpfs) — base PBF + zone build artifacts
#
# CLOUD RUN: set max_instances=1 — registry.json is single-writer.

DATA_DIR="${DATA_DIR:-/data}"
CONFIG_DIR="${CONFIG_DIR:-/config}"
BASE_PBF="${BASE_PBF:-${DATA_DIR}/base/italy.osm.pbf}"
GEOFABRIK_URL="${GEOFABRIK_URL:-https://download.geofabrik.de/europe/italy-latest.osm.pbf}"
ZONE_TTL_DAYS="${ZONE_TTL_DAYS:-90}"
MAX_ACTIVE_ZONES="${MAX_ACTIVE_ZONES:-20}"
OSRM_DEFAULT_RADIUS="${OSRM_DEFAULT_RADIUS:-50}"
UVICORN_WORKERS="${UVICORN_WORKERS:-1}"
LOG_LEVEL="${LOG_LEVEL:-info}"

mkdir -p "${DATA_DIR}/base" "${DATA_DIR}/zones" "${CONFIG_DIR}"
ulimit -n 65536 2>/dev/null || true

echo "=== osrm-zone-manager boot ==="
echo "DATA_DIR=${DATA_DIR} (ephemeral)"
echo "CONFIG_DIR=${CONFIG_DIR} (GCS/persistent)"
echo "BASE_PBF=${BASE_PBF}"
echo "ZONE_TTL_DAYS=${ZONE_TTL_DAYS}"
echo "MAX_ACTIVE_ZONES=${MAX_ACTIVE_ZONES}"
echo "OSRM_DEFAULT_RADIUS=${OSRM_DEFAULT_RADIUS}"

# --- 1. Start uvicorn (orchestrator) in foreground ---
# Registry is a JSON file at ${CONFIG_DIR}/registry.json (see app/runtime/registry_store.py).
# Boot recovery: reads registry → rebuilds zones from stored polygon/linestrings.
# Base PBF download happens in FastAPI lifespan if missing on ephemeral.
echo "=== starting orchestrator (uvicorn) on :8080 ==="
export DATA_DIR CONFIG_DIR BASE_PBF GEOFABRIK_URL
export ZONE_TTL_DAYS MAX_ACTIVE_ZONES OSRM_DEFAULT_RADIUS

exec uvicorn app.main:app \
    --host 0.0.0.0 \
    --port 8080 \
    --workers "${UVICORN_WORKERS}" \
    --log-level "${LOG_LEVEL}"
