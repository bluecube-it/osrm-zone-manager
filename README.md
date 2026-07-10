# osrm-zone-manager

Single-container multi-zone OSRM + VROOM orchestrator.

One Docker container manages multiple routing zones (polygon-bound + optional
custom linestrings). Each active zone runs as `osrm-routed` + `vroom-express`
subprocesses on loopback ports. FastAPI gateway on `:8080` proxies per-zone
traffic and injects DRT radiuses.


## Quick start

```bash
docker build -t osrm-zone-manager .

# /config = persistent (GCS bucket / host dir) — registry.json
# /data   = ephemeral (emptyDir / tmpfs) — PBF + build artifacts
docker run -d \
  --name osrm-zone-manager \
  -p 8080:8080 \
  -v $(pwd)/config:/config \
  -v osrm-zone-manager-data:/data \
  -e ZONE_TTL_DAYS=90 \
  -e MAX_ACTIVE_ZONES=20 \
  -e OSRM_DEFAULT_RADIUS=50 \
  osrm-zone-manager
```

First `POST /zones` triggers Geofabrik download of `italy-latest.osm.pbf`
(~2GB) into `/data/base/` if missing. Pre-mount a PBF at
`/data/base/italy.osm.pbf` to skip download.

## API

| Endpoint | Method | Purpose |
|---|---|---|
| `POST /zones` | POST | Create zone (polygon + optional linestrings GeoJSON) |
| `GET /zones` | GET | List zones with status |
| `GET /zones/:id` | GET | Zone metadata |
| `DELETE /zones/:id` | DELETE | Stop + cleanup zone |
| `/:id/osrm/*` | GET/POST | Proxy to zone's osrm-routed (radiuses injected) |
| `/:id/vroom/*` | GET/POST | Proxy to zone's vroom-express |
| `/health` | GET | Healthcheck |

## Architecture

Single container:
- FastAPI (uvicorn) — gateway + radiuses middleware
- JSON file registry (`/config/registry.json`) — zone registry + last_access tracking
- Per active zone (subprocesses, NOT containers):
  - `osrm-routed --algorithm mld -i 127.0.0.1 -p 5XXX /data/zones/<id>/map.osrm`
  - `vroom-express` on `3XXX` (config.yml templated per-zone, points at `5XXX`)
- Builder (asyncio): osmium extract → reduce.py → osmium merge → osrm-extract/partition/customize
- Evictor (asyncio task): TTL by last_access, never evicts `building` zones

Storage layout:
- `/config` — GCS FUSE bucket (persistent) — `registry.json` only (polygon, linestrings, zone metadata)
- `/data` — ephemeral (emptyDir / tmpfs) — base PBF + zone build artifacts
  - `/data/base/italy.osm.pbf` — source PBF (downloaded at boot if missing)
  - `/data/zones/<id>/` — `map.osrm.*`, `vroom-express/config.yml`, `polygon.geojson`, `linestrings.geojson`
- On boot: reads registry.json → rebuilds zones from stored polygon/linestrings

## Versions

| Component | Version |
|---|---|
| OSRM backend | v26.4 |
| VROOM | v1.15.0 |
| vroom-express | bundled with vroom-docker:v1.15.0 |
| osmium-tool | 1.16.0 |

## Environment

| Var | Default | Purpose |
|---|---|---|
| `CONFIG_DIR` | `/config` | GCS FUSE mount (persistent) — registry.json |
| `DATA_DIR` | `/data` | Ephemeral data root (emptyDir / tmpfs) |
| `BASE_PBF` | `/data/base/italy.osm.pbf` | Source PBF path (downloaded if missing) |
| `GEOFABRIK_URL` | `https://download.geofabrik.de/europe/italy-latest.osm.pbf` | Auto-download source |
| `ZONE_TTL_DAYS` | `90` | Evict zones not accessed in N days |
| `MAX_ACTIVE_ZONES` | `20` | Hard cap on concurrent active zones |
| `OSRM_DEFAULT_RADIUS` | `50` | Radiuses injected (meters) for /route and /table |
| `UVICORN_WORKERS` | `1` | Uvicorn workers (keep 1 for in-process state) |
| `LOG_LEVEL` | `info` | Uvicorn log level |

## License

Combines components under different OSS licenses. User responsible for compliance.
- OSRM: BSD-2-Clause
- VROOM: BSD-2-Clause
- osmium-tool: GPL-3.0
