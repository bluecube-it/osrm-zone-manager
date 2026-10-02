# osrm-zone-manager

Single-container multi-zone OSRM + VROOM orchestrator.

One Docker container manages multiple routing zones (polygon-bound + optional custom lineStrings). Each active zone runs
its own `osrm-routed` subprocess on a loopback port; VROOM runs in-process, spawned from the shared `vroom` binary per
request. Spring Boot gateway on `:8080` proxies per-zone OSRM traffic (injecting DRT radiuses) and serves VROOM
directly.

On top of the zones, one **global** `osrm-routed` instance per routing profile (`CAR`, `BUS`) is built at boot from the
whole base PBF and served on `/osrm/{profile}/**` — full-map routing with no zone creation.

## Quick start

```bash
docker build -t osrm-zone-manager .

# Packaging smoke test (binaries, shared libraries, python deps, real
# osmium -> osrm-extract -> osrm-routed -> vroom pipeline on a synthetic map)
docker run --rm -v "$PWD/.github/scripts:/test:ro" --entrypoint bash \
  osrm-zone-manager /test/docker-smoke-test.sh

# /config = persistent (GCS bucket / host dir) — config/backups
# /data   = ephemeral (emptyDir / tmpfs) — PBF + build artifacts
docker run -d \
  --name osrm-zone-manager \
  -p 8080:8080 \
  -v $(pwd)/config:/config \
  -v osrm-zone-manager-data:/data \
  -e ZONE_TTL_DAYS=90 \
  -e OSRM_DEFAULT_RADIUS=50 \
  -e VROOM_THREADS=6 \
  -e VROOM_MAX_CONCURRENT=16 \
  -e EVICTOR_INTERVAL_MIN=10 \
  -e GLOBAL_OSRM_ENABLED=true \
  osrm-zone-manager
```

Pre-mount PBF at `/data/base/italy.osm.pbf`. Missing PBF returns HTTP 503.

## API

| Endpoint            | Method   | Purpose                                                                              |
|---------------------|----------|--------------------------------------------------------------------------------------|
| `POST /zones`       | POST     | Create zone (polygon + optional lineStrings + optional profile)                      |
| `GET /zones`        | GET      | List zones with status                                                               |
| `GET /zones/:id`    | GET      | Zone metadata                                                                        |
| `DELETE /zones/:id` | DELETE   | Stop + cleanup zone                                                                  |
| `DELETE /zones`     | DELETE   | Stop + cleanup ALL zones                                                             |
| `/:id/osrm/*`       | GET/POST | Proxy to zone's osrm-routed (radiuses injected)                                      |
| `/osrm`             | GET      | Status of the global whole-map instances (one per profile)                           |
| `/osrm/:profile/*`  | GET/POST | Proxy to the global whole-map osrm-routed of that profile                            |
| `/:id/vroom`        | POST     | VROOM solve, in-process (`vroom` binary; same body/status contract as vroom-express) |
| `/:id/vroom/health` | GET      | Probe the `vroom` binary for the zone (empty body, status only)                      |
| `/actuator/health`  | GET      | Healthcheck                                                                          |

## Global profiles (whole map)

At every boot the manager also builds and starts one `osrm-routed` per profile directly from the whole base PBF, so
clients can route on the full map without registering a zone:

```bash
curl 'http://localhost:8080/osrm/car/route/v1/driving/9.19,45.48;9.20,45.47'
curl 'http://localhost:8080/osrm/BUS/table/v1/driving/9.19,45.48;9.20,45.47'  # profile case-insensitive
curl http://localhost:8080/osrm                                              # status per profile
```

- Preprocessed graphs live in `/data/global/<profile>` (`map.osrm.*`) and are reused across restarts. The marker
  `map.fingerprint` records the OSRM version and the base PBF mtime: the graph is rebuilt whenever either changes (a
  new OSRM release in the image makes older graphs unreadable, and rebuilding is the only fix).
- `GET /osrm` always lists every profile — they are registered up-front, so a profile waiting for its turn reads
  `PENDING`, then `BUILDING`, `STARTING`, `READY`.
- The build (`osrm-extract` → `osrm-partition` → `osrm-customize`) runs asynchronously at startup, one profile after
  the other, with a per-stage timeout of `GLOBAL_BUILD_TIMEOUT_SECONDS` (default 7200). Whole-Italy graphs need several
  GB of disk and a long first boot; readiness of the container is not blocked by it.
- **All builds share one slot**: zone builds and whole-map builds never run at the same time, because a single
  `osrm-extract` already peaks at roughly 6–7× the size of its input PBF (≈15 GB for the whole of Italy) and two
  concurrent runs OOM-kill a 16 GB host. Builds that find the slot taken *wait* — they queue, they do not fail — and
  zone builds take precedence over whole-map ones, so creating a zone cannot be starved by a running profile
  rebuild.
- Requests for a profile that is `PENDING`/`BUILDING`/`STARTING`, has `FAILED`, or is unknown get HTTP 503 (unknown
  profile names get HTTP 400). `DEGRADED` profiles still serve traffic.
- Radiuses are injected exactly like in the zone proxy (`OSRM_DEFAULT_RADIUS`, header `x-osrm-radius`).
- Set `GLOBAL_OSRM_ENABLED=false` to skip global instances entirely (zone-only operation).

## Profiles

OSRM bakes the routing profile into the preprocessed graph, so a profile cannot be selected at
query time. Instead the profile is fixed when the zone is created:

```bash
curl -X POST http://localhost:8080/zones \
  -H 'Content-Type: application/json' \
  -d '{"polygon": {...}, "profile": "BUS"}'
```

- `profile` is optional; accepted values are `CAR` (default) and `BUS` (case-insensitive), modelled by
  the `ZoneProfile` enum. Only the profile name is sent — the Lua script paths live in the container
  (`osrm.zone-manager.car-lua=/opt/car.lua`, `osrm.zone-manager.bus-lua=/opt/bus.lua`) and use the
  lower-case file names.
- Unknown profiles are rejected with HTTP 400.
- The profile is part of the zone identity: the same polygon with `CAR` and `BUS` yields two
  distinct zones (ids created before profiles existed keep their id for `CAR`).
- All proxied requests under `/:id/osrm/*` use the zone's profile; the `{profile}` path segment of
  the OSRM URL is ignored by OSRM and by the gateway. For whole-map routing use `/osrm/:profile/*`,
  where the profile segment *is* meaningful (see [Global profiles](#global-profiles-whole-map)).
- VROOM requests run in-process: the gateway spawns the shared `vroom` binary per request, registering the
  zone's OSRM instance under the profile name derived from the zone profile (`car` / `bus`; `car` is kept as an
  alias because VROOM defaults vehicles without an explicit `profile` to `car`). No per-zone node process and no
  per-zone VROOM port are involved.

## Architecture

Single container:

- Spring Boot (virtual threads) — gateway + radiuses middleware
- PostgreSQL database — zone registry + last_access tracking
- Per active zone (subprocesses, NOT containers):
    - `osrm-routed --algorithm mld -i 127.0.0.1 -p 5XXX /data/zones/<id>/map.osrm`
- Global whole-map instances (one per profile, subprocesses, no zone/DB record):
    - `osrm-routed --algorithm mld -i 127.0.0.1 -p 5XXX /data/global/<profile>/map.osrm`, started at boot from the
      base PBF and health-checked by the same supervisor logic
- VROOM (no per-zone process, no per-zone port): the `vroom` binary is spawned per request
  (`-r osrm -a <profile>:127.0.0.1 -p <profile>:5XXX`, request body on stdin), bounded by
  `osrm.zone-manager.vroom-max-concurrent`
- Builder (async): osmium extract → reduce.py → osmium merge → osrm-extract/partition/customize (`-p` = zone profile,
  default `car`), then writes the `map.fingerprint` marker (OSRM version + base PBF mtime)
- Evictor (`@Scheduled`): TTL by last_access, never evicts `building` zones

Storage layout:

- `/config` — GCS FUSE bucket (persistent) — zone registry backups/config
- `/data` — ephemeral (emptyDir / tmpfs) — base PBF + zone build artifacts
    - `/data/base/italy.osm.pbf` — source PBF (pre-mounted)
    - `/data/zones/<id>/` — `map.osrm.*`, `map.fingerprint`, `polygon.geojson`, `lineStrings.geojson`
    - `/data/global/<profile>/` — whole-map `map.osrm.*` + `map.fingerprint` marker
- On boot: reads PostgreSQL registry → starts the zones whose map is still loadable, rebuilds the others (including
  `FAILED` ones) from the stored polygon/lineStrings; then builds/starts the global whole-map instances
- Map fingerprint: a map produced by a different OSRM release cannot be loaded (`osrm_fingerprint.meta` error), so
  bumping the OSRM version in the image invalidates every zone and global map and triggers an automatic rebuild

## Versions

| Component    | Version                                          |
|--------------|--------------------------------------------------|
| Base image   | Debian 13 (trixie) slim                          |
| Java         | 25 (openjdk-25-jre-headless)                     |
| Spring Boot  | 4.1.0                                            |
| OSRM backend | v26.10.0 (`-debian`: no alpine build since 26.7) |
| VROOM        | v1.15.0 (binary, spawned in-process)             |
| osmium-tool  | 1.19.0 (built from source, static)               |
| python deps  | pyosmium (pip), shapely (apt)                    |

## Environment

| Var                            | Default                    | Purpose                                                                        |
|--------------------------------|----------------------------|--------------------------------------------------------------------------------|
| `DATA_DIR`                     | `/data`                    | Ephemeral data root (emptyDir / tmpfs)                                         |
| `BASE_PBF`                     | `/data/base/italy.osm.pbf` | Source PBF path (must exist, no auto-download)                                 |
| `DB_HOST`                      | `localhost`                | PostgreSQL host                                                                |
| `DB_PORT`                      | `5432`                     | PostgreSQL port                                                                |
| `DB_NAME`                      | `osrm_zone_manager`        | PostgreSQL database name                                                       |
| `DB_USERNAME`                  | `osrm`                     | PostgreSQL username                                                            |
| `DB_PASSWORD`                  | `osrm`                     | PostgreSQL password                                                            |
| `ZONE_TTL_DAYS`                | `90`                       | Evict zones not accessed in N days                                             |
| `OSRM_DEFAULT_RADIUS`          | `50`                       | Radiuses injected (meters) for /route and /table                               |
| `OSRM_START_TIMEOUT_SECONDS`   | `120`                      | Budget for a zone `osrm-routed` to answer the health probe before `FAILED`     |
| `VROOM_THREADS`                | `6`                        | Solving threads handed to each `vroom` run (`-t`)                              |
| `VROOM_MAX_CONCURRENT`         | `16`                       | Max concurrent `vroom` processes; extra requests queue                         |
| `EVICTOR_INTERVAL_MIN`         | `10`                       | Evictor interval in minutes                                                    |
| `GLOBAL_OSRM_ENABLED`          | `true`                     | Build/start one whole-map `osrm-routed` per profile at boot                    |
| `GLOBAL_BUILD_TIMEOUT_SECONDS` | `7200`                     | Per-stage timeout for the whole-map build (`osrm-extract/partition/customize`) |
| `LOG_LEVEL`                    | `info`                     | Spring log level (mapped to `logging.level.it.bluecube.osrmzonemanager`)       |

## VROOM tuning

Properties (prefix `osrm.zone-manager`, see `application.properties`). Sizing rule: keep
`vroom-threads × vroom-max-concurrent` close to the available CPU cores — each `vroom` run is
multi-threaded and mostly waits on OSRM `/table`, so a moderate oversubscription is fine.

| Property                     | Default                         | Purpose                                                                |
|------------------------------|---------------------------------|------------------------------------------------------------------------|
| `vroom-binary`               | `vroom`                         | Binary spawned per request                                             |
| `vroom-threads`              | `6`                             | Solving threads (`-t`), `VROOM_THREADS` env override                   |
| `vroom-explore`              | `5`                             | Exploration level 0..5 (`-x`)                                          |
| `vroom-geometry`             | `false`                         | Default for `-g`                                                       |
| `vroom-choose-eta`           | `false`                         | Default for `-c` (choose ETA for custom routes)                        |
| `vroom-limit-seconds`        | `0`                             | Default for `-l`; `0` omits the flag                                   |
| `vroom-timeout-ms`           | `300000`                        | Wall-clock budget per run; the process is killed on expiry             |
| `vroom-max-locations`        | `1000`                          | Max `jobs + 2 * shipments` per request (413, code 4 beyond)            |
| `vroom-max-vehicles`         | `200`                           | Max vehicles per request (413, code 4 beyond)                          |
| `vroom-max-body-bytes`       | `1048576`                       | Max request body size (413, code 4 beyond)                             |
| `vroom-override`             | `c,g,l,t,x`                     | Options a request may override via its `options` object                |
| `vroom-max-concurrent`       | `16`                            | Max concurrent `vroom` processes (`VROOM_MAX_CONCURRENT` env override) |
| `vroom-healthcheck-resource` | `config/vroom_healthcheck.json` | Classpath payload used by `/:id/vroom/health`                          |

## License

Combines components under different OSS licenses. User responsible for compliance.

- OSRM: BSD-2-Clause
- VROOM: BSD-2-Clause
- osmium-tool: GPL-3.0
