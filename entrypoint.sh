#!/bin/bash
set -e

DATA_DIR="${DATA_DIR:-/data}"
BASE_PBF="${BASE_PBF:-${DATA_DIR}/base/italy.osm.pbf}"
OSRM_DEFAULT_RADIUS="${OSRM_DEFAULT_RADIUS:-50}"
EVICTOR_INTERVAL_MIN="${EVICTOR_INTERVAL_MIN:-10}"
LOG_LEVEL="${LOG_LEVEL:-info}"

mkdir -p "${DATA_DIR}/base" "${DATA_DIR}/zones"
ulimit -n 65536 2>/dev/null || true

echo "=== osrm-zone-manager boot ==="
echo "DATA_DIR=${DATA_DIR}"
echo "BASE_PBF=${BASE_PBF}"
echo "SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL:-<not set>}"
echo "OSRM_DEFAULT_RADIUS=${OSRM_DEFAULT_RADIUS}"

exec java -Duser.timezone=UTC \
  -XX:+UseZGC \
  -Dosrm.zone-manager.data-dir="${DATA_DIR}" \
  -Dosrm.zone-manager.base-pbf="${BASE_PBF}" \
  -Dosrm.zone-manager.osrm-default-radius="${OSRM_DEFAULT_RADIUS}" \
  -Dosrm.zone-manager.evictor-interval-minutes="${EVICTOR_INTERVAL_MIN}" \
  -Dlogging.level.it.bluecube.osrmzonemanager="${LOG_LEVEL}" \
  -jar /app/application.jar
