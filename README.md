# osrm-zone-manager

Single-container multi-zone OSRM + VROOM orchestrator.

One Docker container manages multiple routing zones (polygon-bound + optional custom lineStrings). Each active zone runs
as `osrm-routed` + `vroom-express`
subprocesses on loopback ports. Spring Boot gateway on `:8080` proxies per-zone traffic and injects DRT radiuses.

## Quick start

```bash
docker build -t osrm-zone-manager .

# /config = persistent (GCS bucket / host dir) — config/backups
# /data   = ephemeral (emptyDir / tmpfs) — PBF + build artifacts
docker run -d \
  --name osrm-zone-manager \
  -p 8080:8080 \
  -v $(pwd)/config:/config \
  -v osrm-zone-manager-data:/data \
  -e ZONE_TTL_DAYS=90 \
  -e OSRM_DEFAULT_RADIUS=50 \
  -e EVICTOR_INTERVAL_MIN=10 \
  osrm-zone-manager
```

Pre-mount PBF at `/data/base/italy.osm.pbf`. Missing PBF returns HTTP 503.

## API

| Endpoint            | Method   | Purpose                                              |
|---------------------|----------|------------------------------------------------------|
| `POST /zones`       | POST     | Create zone (polygon + optional lineStrings GeoJSON) |
| `GET /zones`        | GET      | List zones with status                               |
| `GET /zones/:id`    | GET      | Zone metadata                                        |
| `DELETE /zones/:id` | DELETE   | Stop + cleanup zone                                  |
| `DELETE /zones`     | DELETE   | Stop + cleanup ALL zones                             |
| `/:id/osrm/*`       | GET/POST | Proxy to zone's osrm-routed (radiuses injected)      |
| `/:id/vroom/*`      | GET/POST | Proxy to zone's vroom-express                        |
| `/actuator/health`  | GET      | Healthcheck                                          |

## Architecture

Single container:

- Spring Boot (virtual threads) — gateway + radiuses middleware
- PostgreSQL database — zone registry + last_access tracking
- Per active zone (subprocesses, NOT containers):
    - `osrm-routed --algorithm mld -i 127.0.0.1 -p 5XXX /data/zones/<id>/map.osrm`
    - `vroom-express` on `3XXX` (config.yml templated per-zone, points at `5XXX`)
- Builder (async): osmium extract → reduce.py → osmium merge → osrm-extract/partition/customize
- Evictor (`@Scheduled`): TTL by last_access, never evicts `building` zones

Storage layout:

- `/config` — GCS FUSE bucket (persistent) — zone registry backups/config
- `/data` — ephemeral (emptyDir / tmpfs) — base PBF + zone build artifacts
    - `/data/base/italy.osm.pbf` — source PBF (pre-mounted)
    - `/data/zones/<id>/` — `map.osrm.*`, `vroom-express/config.yml`, `polygon.geojson`, `lineStrings.geojson`
- On boot: reads PostgreSQL registry → rebuilds zones from stored polygon/lineStrings

## Versions

| Component     | Version                           |
|---------------|-----------------------------------|
| Java          | 25                                |
| Spring Boot   | 4.1.0                             |
| OSRM backend  | v26.4                             |
| VROOM         | v1.15.0                           |
| vroom-express | bundled with vroom-docker:v1.15.0 |
| osmium-tool   | 1.16.0                            |

## Environment

| Var                    | Default                    | Purpose                                                                  |
|------------------------|----------------------------|--------------------------------------------------------------------------|
| `DATA_DIR`             | `/data`                    | Ephemeral data root (emptyDir / tmpfs)                                   |
| `BASE_PBF`             | `/data/base/italy.osm.pbf` | Source PBF path (must exist, no auto-download)                           |
| `DB_HOST`              | `localhost`                | PostgreSQL host                                                          |
| `DB_PORT`              | `5432`                     | PostgreSQL port                                                          |
| `DB_NAME`              | `osrm_zone_manager`        | PostgreSQL database name                                                 |
| `DB_USERNAME`          | `osrm`                     | PostgreSQL username                                                      |
| `DB_PASSWORD`          | `osrm`                     | PostgreSQL password                                                      |
| `ZONE_TTL_DAYS`        | `90`                       | Evict zones not accessed in N days                                       |
| `OSRM_DEFAULT_RADIUS`  | `50`                       | Radiuses injected (meters) for /route and /table                         |
| `EVICTOR_INTERVAL_MIN` | `10`                       | Evictor interval in minutes                                              |
| `LOG_LEVEL`            | `info`                     | Spring log level (mapped to `logging.level.it.bluecube.osrmzonemanager`) |

## License

Combines components under different OSS licenses. User responsible for compliance.

- OSRM: BSD-2-Clause
- VROOM: BSD-2-Clause
- osmium-tool: GPL-3.0
