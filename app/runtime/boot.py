"""Boot recovery: read registry active zones, verify, restart subprocesses.

On container boot:
- iterate registry zones
- for each: verify files exist, content-hash matches, restart processes
- partial builds (status=building) resume pipeline
- stale PBF detection
"""

import os

from app.config import config
from app.runtime.redis_client import (
    get_zone,
    list_zones,
    set_zone_status,
)
from app.runtime.supervisor import start_health_checker, start_zone
from app.utils.logger import get_logger

log = get_logger(__name__)


async def recover_zones() -> None:
    """Walk registry, recover zone state, start subprocesses where needed."""
    zone_ids = await list_zones()

    if not zone_ids:
        log.info("boot recovery: no zones in registry")
        # Health checker may still want to start for zones added after boot
        _ = await start_health_checker()
        return

    log.info("boot recovery: found %d zone(s) in registry", len(zone_ids))

    # Current base PBF mtime for staleness check
    now_mtime = _file_mtime(config.base_pbf) if os.path.isfile(config.base_pbf) else 0

    for zid in zone_ids:
        zone = await get_zone(zid)
        if not zone:
            await set_zone_status(zid, "failed", error="registry record missing")
            continue

        status = zone.get("status", "failed")

        if status == "building":
            await _recover_building(zid, zone, now_mtime)
        elif status in ("active", "degraded"):
            await _recover_active(zid, zone, now_mtime)
        elif status in ("built", "starting"):
            await _recover_built(zid, zone, now_mtime)
        else:
            log.warning("boot recovery: zone %s status='%s' — skipping", zid, status)

    # Start health checker for ongoing monitoring
    _ = await start_health_checker()


async def _recover_building(zid: str, zone: dict, current_pbf_mtime: float) -> None:
    """Resume a partial build from the builder pipeline."""
    log.info("boot recovery: zone %s is building — resuming pipeline", zid)

    await set_zone_status(
        zid, "failed",
        error="building at shutdown — polygon not stored, manual rebuild required"
    )
    log.warning("boot recovery: zone %s building at shutdown — cannot resume (polygon not in registry)", zid)


async def _recover_active(zid: str, zone: dict, current_pbf_mtime: float) -> None:
    """Verify and restart an active zone."""
    zone_dir = f"{config.zones_dir}/{zid}"
    map_file = f"{zone_dir}/map.osrm.properties"

    # ── 1. file existence check ──────────────────────────────────────
    if not os.path.isfile(map_file):
        log.warning("boot recovery: zone %s: %s missing — marking failed", zid, map_file)
        await set_zone_status(zid, "failed", error="map.osrm.properties missing on disk")
        return

    # ── 2. content-hash verification ────────────────────────────────
    polygon_hash = zone.get("polygon_hash", "")
    linestrings_hash = zone.get("linestrings_hash", "")
    expected_pbf_mtime = zone.get("base_pbf_mtime", "")

    # Check base PBF mtime for staleness
    if current_pbf_mtime and expected_pbf_mtime:
        try:
            if float(expected_pbf_mtime) != current_pbf_mtime:
                log.warning(
                    "boot recovery: zone %s: PBF mtime mismatch "
                    "(registered=%s current=%.2f) — marking stale",
                    zid, expected_pbf_mtime, current_pbf_mtime,
                )
                await set_zone_status(zid, "stale")
                return
        except (ValueError, TypeError):
            pass

    # Read polygon/linestrings from disk if they exist to verify hashes
    poly_file = f"{zone_dir}/polygon.geojson"
    ls_file = f"{zone_dir}/linestrings.geojson"

    if os.path.isfile(poly_file):
        with open(poly_file, "rb") as f:
            content = f.read()
        import hashlib
        actual_poly_hash = hashlib.sha256(content).hexdigest()
        if actual_poly_hash != polygon_hash:
            log.warning(
                "boot recovery: zone %s: polygon hash mismatch "
                "(registry=%s disk=%s) — mark failed", zid, polygon_hash[:12], actual_poly_hash[:12]
            )
            await set_zone_status(zid, "failed", error="content-hash mismatch")
            return

    if linestrings_hash and os.path.isfile(ls_file):
        with open(ls_file, "rb") as f:
            content = f.read()
        actual_ls_hash = hashlib.sha256(content).hexdigest()
        if actual_ls_hash != linestrings_hash:
            log.warning(
                "boot recovery: zone %s: linestrings hash mismatch — mark failed", zid
            )
            await set_zone_status(zid, "failed", error="content-hash mismatch")
            return
    # ── 3. start subprocesses ──────────────────────────────────────
    try:
        await start_zone(zid)
        if zid in _get_all_zone_ids():
            log.info("boot recovery: zone %s restarted successfully", zid)
        else:
            await set_zone_status(zid, "failed", error="startup failed during recovery")
    except Exception as exc:
        log.error("boot recovery: zone %s restart failed: %s", zid, exc)
        await set_zone_status(zid, "failed", error=str(exc))


def _file_mtime(path: str) -> float:
    return os.path.getmtime(path) if os.path.isfile(path) else 0.0


async def _recover_built(zid: str, zone: dict, current_pbf_mtime: float) -> None:
    """Recover a zone that built files but didn't start subprocesses.

    Triggers start_zone (same path as _start_after_build). Skips hash checks
    since build already verified them.
    """
    zone_dir = f"{config.zones_dir}/{zid}"
    map_file = f"{zone_dir}/map.osrm.properties"
    if not os.path.isfile(map_file):
        log.warning("boot recovery: zone %s: %s missing — marking failed", zid, map_file)
        await set_zone_status(zid, "failed", error="map.osrm.properties missing on disk")
        return
    log.info("boot recovery: zone %s status='%s' — starting subprocesses", zid, zone.get("status"))
    try:
        await set_zone_status(zid, "starting")
        await start_zone(zid)
        if zid in _get_all_zone_ids():
            log.info("boot recovery: zone %s recovered from built → active", zid)
        else:
            await set_zone_status(zid, "failed", error="startup failed during recovery")
    except Exception as exc:
        log.error("boot recovery: zone %s start failed: %s", zid, exc)
        await set_zone_status(zid, "failed", error=str(exc))


def _get_all_zone_ids() -> set:
    from app.runtime.supervisor import all_zone_ids
    return all_zone_ids()
