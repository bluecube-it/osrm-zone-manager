# osrm-zone-manager

Single-container multi-zone OSRM + VROOM orchestrator.

One Docker container manages multiple routing zones (polygon-bound + optional
custom linestrings). Each active zone runs as `osrm-routed` + `vroom-express`
subprocesses on loopback ports. FastAPI gateway on `:8080` proxies per-zone
traffic and injects DRT radiuses.


## Quick start

```bash
docker build -t osrm-zone-manager .

# /data persisted on host
docker run -d \
  --name osrm-zone-manager \
  -p 8080:8080 \
  -v $(pwd)/data:/data \
  -e ZONE_TTL_DAYS=90 \
  -e MAX_ACTIVE_ZONES=20 \
  -e OSRM_DEFAULT_RADIUS=50 \
  osrm-zone-manager
```

First `POST /zones` triggers Geofabrik download of `italy-latest.osm.pbf`
(~2GB) into `/data/base/` if missing.

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
- JSON file registry (`/data/registry.json`) — zone registry + last_access tracking
- Per active zone (subprocesses, NOT containers):
  - `osrm-routed --algorithm mld -i 127.0.0.1 -p 5XXX /data/zones/<id>/map.osrm`
  - `vroom-express` on `3XXX` (config.yml templated per-zone, points at `5XXX`)
- Builder (asyncio): osmium extract → reduce.py → osmium merge → osrm-extract/partition/customize
- Evictor (asyncio task): TTL by last_access, never evicts `building` zones

Persistence (`/data` mount):
- `/data/base/italy.osm.pbf` — source PBF (downloaded once)
- `/data/zones/<id>/` — `map.osrm.*`, `reduced.pbf`, `polygon.geojson`, `linestrings.geojson`, `config.yml`
- `/data/registry.json` — zone registry (JSON, atomic write via `os.replace` for GCS FUSE compatibility)

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
| `DATA_DIR` | `/data` | Persistent data root |
| `BASE_PBF` | `/data/base/italy.osm.pbf` | Source PBF path |
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
