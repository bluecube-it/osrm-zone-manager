#!/usr/bin/env bash
#
# End-to-end test for the global whole-map instances (`/osrm/{profile}/**`).
#
# Unlike the Maven ITs (which stub the build pipeline and the process supervisor), this test runs
# the real app from the image against a *tiny* synthetic PBF, so it exercises the whole boot path:
#   base pbf -> osrm-extract/partition/customize per profile -> osrm-routed -> HTTP proxy
# and then verifies the pre-existing zone API still works on the same map.
#
# Requires: docker (a Postgres container is started by this script).
#
# Run locally with:
#   docker build -t osrm-zone-manager:global-test .
#   .github/scripts/docker-global-osrm-test.sh
set -euo pipefail

IMAGE="${IMAGE:-osrm-zone-manager:global-test}"
APP_PORT="${APP_PORT:-18080}"
APP_CONTAINER="${APP_CONTAINER:-ozm-global-app}"
DB_CONTAINER="${DB_CONTAINER:-ozm-global-db}"
NETWORK="${NETWORK:-ozm-global-net}"
READY_TIMEOUT="${READY_TIMEOUT:-300}"

workdir="$(mktemp -d)"

dump_logs() {
  echo "--- app log (tail)" >&2
  docker logs "$APP_CONTAINER" 2>&1 | tail -60 >&2 || true
}

fail() {
  echo "FAIL: $*" >&2
  dump_logs
  exit 1
}

# Removes the workdir. The app runs as the caller's uid so its artifacts are removable directly;
# if anything is still root-owned (e.g. an older image running as root), fall back to deleting
# inside a throw-away container.
remove_workdir() {
  [ -d "$workdir" ] || return 0
  rm -rf "$workdir" 2>/dev/null && return 0
  docker run --rm -v "$workdir:/work" --entrypoint sh "$IMAGE" -c 'rm -rf /work/* 2>/dev/null || true' \
    >/dev/null 2>&1 || true
  rm -rf "$workdir" 2>/dev/null || true
}

cleanup() {
  docker rm -f "$APP_CONTAINER" "$DB_CONTAINER" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  remove_workdir
}
trap cleanup EXIT

api() {
  curl -s -o /tmp/ozm-response.json -w '%{http_code}' "$@"
}

echo "== tiny map"
mkdir -p "$workdir/data/base"
cat > "$workdir/tiny.osm" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="docker-global-osrm-test">
  <node id="1" lat="45.4800" lon="9.1900" version="1"/>
  <node id="2" lat="45.4780" lon="9.1920" version="1"/>
  <node id="3" lat="45.4760" lon="9.1940" version="1"/>
  <node id="4" lat="45.4740" lon="9.1960" version="1"/>
  <way id="10" version="1">
    <nd ref="1"/>
    <nd ref="2"/>
    <tag k="highway" v="secondary"/>
    <tag k="name" v="Via Uno"/>
  </way>
  <way id="11" version="1">
    <nd ref="2"/>
    <nd ref="3"/>
    <nd ref="4"/>
    <tag k="highway" v="secondary"/>
    <tag k="name" v="Via Due"/>
  </way>
</osm>
XML
docker run --rm -v "$workdir:/work" --entrypoint osmium "$IMAGE" \
  cat /work/tiny.osm -o /work/data/base/tiny.osm.pbf
[ -s "$workdir/data/base/tiny.osm.pbf" ] || fail "tiny pbf was not produced"

echo "== postgres"
docker network create "$NETWORK" >/dev/null
docker run -d --name "$DB_CONTAINER" --network "$NETWORK" \
  -e POSTGRES_DB=osrm_zone_manager -e POSTGRES_USER=osrm -e POSTGRES_PASSWORD=osrm \
  postgres:18-alpine >/dev/null
for _ in $(seq 1 60); do
  if docker exec "$DB_CONTAINER" pg_isready -U osrm -d osrm_zone_manager >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
docker exec "$DB_CONTAINER" pg_isready -U osrm -d osrm_zone_manager >/dev/null 2>&1 \
  || fail "postgres did not become ready"

echo "== app with global profiles enabled"
docker run -d --name "$APP_CONTAINER" --network "$NETWORK" -p "${APP_PORT}:8080" \
  --user "$(id -u):$(id -g)" \
  -v "$workdir/data:/data" \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://${DB_CONTAINER}:5432/osrm_zone_manager" \
  -e SPRING_DATASOURCE_USERNAME=osrm \
  -e SPRING_DATASOURCE_PASSWORD=osrm \
  -e BASE_PBF=/data/base/tiny.osm.pbf \
  -e OSRM_ZONE_MANAGER_MIN_PBF_SIZE=1 \
  -e GLOBAL_OSRM_ENABLED=true \
  -e LOG_LEVEL=debug \
  "$IMAGE" >/dev/null

base="http://127.0.0.1:${APP_PORT}"
deadline=$((SECONDS + READY_TIMEOUT))
until [ "$(api "$base/actuator/health")" = "200" ]; do
  [ "$SECONDS" -lt "$deadline" ] || fail "app did not become healthy within ${READY_TIMEOUT}s"
  sleep 2
done

echo "== waiting for the global profiles to be READY"
profiles=("CAR" "BUS")
until [ "$(api "$base/osrm")" = "200" ] \
  && grep -q '"profile":"CAR","status":"READY"' /tmp/ozm-response.json \
  && grep -q '"profile":"BUS","status":"READY"' /tmp/ozm-response.json; do
  if grep -q '"status":"FAILED"' /tmp/ozm-response.json 2>/dev/null; then
    fail "global profile failed to build: $(cat /tmp/ozm-response.json)"
  fi
  [ "$SECONDS" -lt "$deadline" ] || fail "global profiles not ready within ${READY_TIMEOUT}s: $(cat /tmp/ozm-response.json)"
  sleep 3
done
cat /tmp/ozm-response.json
echo

route="route/v1/driving/9.1900,45.4800;9.1960,45.4740"

for profile in "${profiles[@]}"; do
  echo "== global route via /osrm/${profile}"
  code="$(api "$base/osrm/${profile}/${route}")"
  [ "$code" = "200" ] || fail "GET /osrm/${profile}/${route} -> HTTP $code: $(cat /tmp/ozm-response.json)"
  grep -q '"code":"Ok"' /tmp/ozm-response.json \
    || fail "global ${profile} route did not return code Ok: $(cat /tmp/ozm-response.json)"
  echo "global ${profile} route ok"
done

echo "== global radius override"
code="$(api -H 'x-osrm-radius: 150' "$base/osrm/car/$route")"
[ "$code" = "200" ] || fail "route with x-osrm-radius -> HTTP $code"

echo "== unknown global profile is rejected"
code="$(api "$base/osrm/truck/$route")"
[ "$code" = "400" ] || fail "unknown profile -> HTTP $code (expected 400)"

echo "== zone API on the same map"
cat > "$workdir/polygon.json" <<'JSON'
{"polygon":{"type":"Polygon","coordinates":[[[9.18,45.47],[9.18,45.49],[9.20,45.49],[9.20,45.47],[9.18,45.47]]]}}
JSON
code="$(api -X POST -H 'Content-Type: application/json' --data @"$workdir/polygon.json" "$base/zones")"
[ "$code" = "201" ] || fail "POST /zones -> HTTP $code: $(cat /tmp/ozm-response.json)"
zone_id="$(sed -n 's/.*"zoneId":"\([^"]*\)".*/\1/p' /tmp/ozm-response.json)"
[ -n "$zone_id" ] || fail "could not read zoneId from $(cat /tmp/ozm-response.json)"
echo "zone ${zone_id} created"

until [ "$(api "$base/zones/${zone_id}")" = "200" ] \
  && grep -q '"status":"ACTIVE"' /tmp/ozm-response.json; do
  if grep -q '"status":"FAILED"' /tmp/ozm-response.json 2>/dev/null; then
    fail "zone build failed: $(cat /tmp/ozm-response.json)"
  fi
  [ "$SECONDS" -lt "$deadline" ] || fail "zone not ACTIVE within ${READY_TIMEOUT}s: $(cat /tmp/ozm-response.json)"
  sleep 2
done

code="$(api "$base/${zone_id}/osrm/${route}")"
[ "$code" = "200" ] || fail "zone proxy -> HTTP $code: $(cat /tmp/ozm-response.json)"
grep -q '"code":"Ok"' /tmp/ozm-response.json \
  || fail "zone route did not return code Ok: $(cat /tmp/ozm-response.json)"
echo "zone route ok"

echo "== global instances still READY after zone traffic"
code="$(api "$base/osrm")"
[ "$code" = "200" ] || fail "GET /osrm -> HTTP $code"
grep -q '"profile":"CAR","status":"READY"' /tmp/ozm-response.json \
  || fail "global CAR stopped being READY: $(cat /tmp/ozm-response.json)"

echo "== map fingerprints written for zones and globals"
for dir in /data/global/car /data/global/bus "/data/zones/${zone_id}"; do
  docker exec "$APP_CONTAINER" test -f "${dir}/map.fingerprint" \
    || fail "missing ${dir}/map.fingerprint"
done
docker exec "$APP_CONTAINER" cat /data/global/car/map.fingerprint
docker exec "$APP_CONTAINER" cat "/data/zones/${zone_id}/map.fingerprint"

echo "== restart: maps must be reused, zone restored, nothing rebuilt"
docker stop "$APP_CONTAINER" >/dev/null
docker start "$APP_CONTAINER" >/dev/null
until [ "$(api "$base/actuator/health")" = "200" ] \
  && [ "$(api "$base/osrm")" = "200" ] \
  && grep -q '"profile":"CAR","status":"READY"' /tmp/ozm-response.json \
  && grep -q '"profile":"BUS","status":"READY"' /tmp/ozm-response.json; do
  [ "$SECONDS" -lt "$deadline" ] || fail "globals not READY after restart: $(cat /tmp/ozm-response.json)"
  sleep 2
done
# note: capture the log first — `docker logs | grep -q` would make docker die on SIGPIPE and
# trip `set -o pipefail`
docker logs "$APP_CONTAINER" > "$workdir/app-restart.log" 2>&1 || true
grep -q "graph already up to date" "$workdir/app-restart.log" \
  || fail "global graphs were rebuilt after restart instead of reused"
grep -q "map is loadable — starting" "$workdir/app-restart.log" \
  || fail "zone was not restored from its existing map after restart"
if grep -q "rebuilding" "$workdir/app-restart.log"; then
  fail "something was rebuilt after restart (fingerprint mismatch?)"
fi

code="$(api "$base/osrm/car/$route")"
[ "$code" = "200" ] || fail "global route after restart -> HTTP $code"
grep -q '"code":"Ok"' /tmp/ozm-response.json || fail "global route after restart not Ok"
code="$(api "$base/${zone_id}/osrm/${route}")"
[ "$code" = "200" ] || fail "zone route after restart -> HTTP $code"
grep -q '"code":"Ok"' /tmp/ozm-response.json || fail "zone route after restart not Ok"
echo "restart reuse ok"

echo "GLOBAL OSRM E2E TEST OK"
