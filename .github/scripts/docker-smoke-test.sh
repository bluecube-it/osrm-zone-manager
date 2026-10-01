#!/usr/bin/env bash
#
# Image smoke test: verifies the packaged toolchain (binaries, shared libraries, python deps,
# lua profiles) and runs a real pipeline on a synthetic map:
#   osmium cat -> osmium extract -> osrm-extract/partition/customize -> osrm-routed -> vroom
#
# Catches packaging regressions that unit/integration tests cannot see: missing system
# libraries after a base-image or version bump, a wrapper script copied instead of a binary,
# a broken lua profile path, or a VROOM binary that cannot talk to OSRM.
#
# Run locally with:
#   docker build -t osrm-zone-manager:smoke .
#   docker run --rm -v "$PWD/.github/scripts:/test:ro" --entrypoint bash \
#     osrm-zone-manager:smoke /test/docker-smoke-test.sh
set -euo pipefail

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

echo "== versions"
osrm-routed --version | head -1
osrm-extract --version | head -1
osrm-partition --version | head -1
osrm-customize --version | head -1
vroom --version
osmium --version | head -1
java -version 2>&1 | head -1

echo "== python deps"
python3 -c "import osmium, osmium.osm, shapely; from shapely.geometry import shape; print('pyosmium + shapely ok')"

echo "== assets"
for asset in /usr/local/bin/vroom \
             /usr/local/bin/osmium \
             /opt/car.lua \
             /opt/bus.lua \
             /app/scripts/reduce.py \
             /app/application.jar; do
  [ -e "$asset" ] || fail "missing asset $asset"
done

echo "== shared libraries"
for binary in osrm-routed osrm-extract osrm-partition osrm-customize vroom osmium; do
  missing=$(ldd "/usr/local/bin/$binary" 2>/dev/null | grep -c "not found" || true)
  [ "$missing" = "0" ] || fail "$binary has $missing unresolved libraries"
done
echo "all binaries resolve their libraries"

workdir=$(mktemp -d)
cd "$workdir"

echo "== synthetic map"
cat > tiny.osm <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="docker-smoke-test">
  <node id="1" lat="45.4800" lon="9.1900" version="1"/>
  <node id="2" lat="45.4780" lon="9.1920" version="1"/>
  <node id="3" lat="45.4760" lon="9.1940" version="1"/>
  <way id="10" version="1">
    <nd ref="1"/>
    <nd ref="2"/>
    <nd ref="3"/>
    <tag k="highway" v="residential"/>
    <tag k="name" v="Smoke Street"/>
  </way>
</osm>
XML
osmium cat tiny.osm -o tiny.osm.pbf

cat > polygon.geojson <<'JSON'
{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[[9.18,45.47],[9.18,45.49],[9.20,45.49],[9.20,45.47],[9.18,45.47]]]}}]}
JSON
osmium extract --overwrite -p polygon.geojson tiny.osm.pbf -o region.osm.pbf

echo "== osrm build"
osrm-extract -p /opt/car.lua -o map.osrm region.osm.pbf
osrm-partition map.osrm
osrm-customize map.osrm

echo "== osrm-routed"
osrm-routed --algorithm mld --ip 127.0.0.1 --port 5001 map.osrm &
osrm_pid=$!
trap 'kill "$osrm_pid" 2>/dev/null || true; rm -rf "$workdir"' EXIT

route="http://127.0.0.1:5001/route/v1/driving/9.1900,45.4800;9.1940,45.4760"
served=false
for _ in $(seq 1 60); do
  if curl -sf "$route" >/dev/null 2>&1; then
    served=true
    break
  fi
  sleep 1
done
[ "$served" = "true" ] || fail "osrm-routed did not serve $route within 60s"
curl -sf "$route" | head -c 120
echo

echo "== vroom against osrm"
cat > request.json <<'JSON'
{"vehicles":[{"id":0,"start":[9.1900,45.4800],"end":[9.1900,45.4800]}],"jobs":[{"id":1,"location":[9.1940,45.4760],"service":60}]}
JSON
vroom -r osrm -a car:127.0.0.1 -p car:5001 -t 2 -x 5 <request.json >solution.json

python3 - <<'PY'
import json
with open('solution.json') as handle:
    solution = json.load(handle)
code = solution.get('code')
if code != 0:
    raise SystemExit('vroom returned code %s: %s' % (code, solution.get('error')))
if solution['summary']['routes'] != 1:
    raise SystemExit('expected 1 route, got %s' % solution['summary'])
print('vroom solution ok, cost %s' % solution['summary']['cost'])
PY

echo "SMOKE TEST OK"
