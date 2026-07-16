#!/bin/bash
set -e

DATA_DIR="${DATA_DIR:-/data}"
CONFIG_DIR="${CONFIG_DIR:-/config}"
BASE_PBF="${BASE_PBF:-${DATA_DIR}/base/italy.osm.pbf}"
GEOFABRIK_URL="${GEOFABRIK_URL:-https://download.geofabrik.de/europe/italy-latest.osm.pbf}"
ZONE_TTL_DAYS="${ZONE_TTL_DAYS:-90}"
OSRM_DEFAULT_RADIUS="${OSRM_DEFAULT_RADIUS:-50}"
EVICTOR_INTERVAL_MIN="${EVICTOR_INTERVAL_MIN:-10}"
LOG_LEVEL="${LOG_LEVEL:-info}"

mkdir -p "${DATA_DIR}/base" "${DATA_DIR}/zones" "${CONFIG_DIR}"
ulimit -n 65536 2>/dev/null || true

echo "=== osrm-zone-manager boot ==="
echo "DATA_DIR=${DATA_DIR}"
echo "CONFIG_DIR=${CONFIG_DIR}"
echo "BASE_PBF=${BASE_PBF}"
echo "ZONE_TTL_DAYS=${ZONE_TTL_DAYS}"
echo "OSRM_DEFAULT_RADIUS=${OSRM_DEFAULT_RADIUS}"

exec java -Duser.timezone=UTC \
  -XX:+UseZGC \
  -Dosrm.zone-manager.data-dir="${DATA_DIR}" \
  -Dosrm.zone-manager.config-dir="${CONFIG_DIR}" \
  -Dosrm.zone-manager.base-pbf="${BASE_PBF}" \
  -Dosrm.zone-manager.geofabrik-url="${GEOFABRIK_URL}" \
  -Dosrm.zone-manager.zone-ttl-days="${ZONE_TTL_DAYS}" \
  -Dosrm.zone-manager.osrm-default-radius="${OSRM_DEFAULT_RADIUS}" \
  -Dosrm.zone-manager.evictor-interval-minutes="${EVICTOR_INTERVAL_MIN}" \
  -Dlogging.level.it.bluecube.osrmzonemanager="${LOG_LEVEL}" \
  -jar /app/application.jar
