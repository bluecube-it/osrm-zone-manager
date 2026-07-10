"""Boot recovery: read registry, rebuild zones from stored polygon/linestrings.

On container boot (ephemeral /data is empty):
- download base PBF if missing
- iterate registry zones
- for each active/built zone: re-run build_zone from stored polygon/linestrings
- stale PBF detection via mtime (always 0 on fresh ephemeral = rebuild all)
"""

import os

from app.config import config
from app.runtime.registry_store import (
    get_zone,
    list_zones,
    set_zone_status,
)
from app.runtime.supervisor import start_health_checker, start_zone
from app.utils.logger import get_logger
from app.builder.pipeline import build_zone

log = get_logger(__name__)


async def recover_zones() -> None:
    """Walk registry, recover zone state, start subprocesses where needed."""
    zone_ids = await list_zones()

    if not zone_ids:
        log.info("boot recovery: no zones in registry")
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

    _ = await start_health_checker()


async def _recover_building(zid: str, zone: dict, current_pbf_mtime: float) -> None:
    """Resume a partial build — re-run build_zone from stored polygon/linestrings."""
    polygon = zone.get("polygon_geojson")
    if not polygon:
        await set_zone_status(
            zid, "failed",
            error="building at shutdown — polygon not in registry, cannot rebuild"
        )
        log.warning("boot recovery: zone %s: no polygon in registry, cannot rebuild", zid)
        return

    linestrings = zone.get("linestrings_geojson") or None
    log.info("boot recovery: zone %s: resuming build from registry", zid)
    await set_zone_status(zid, "building")
    result = await build_zone(zid, polygon, linestrings)
    if result.ok:
        await _start_after_rebuild(zid)
    else:
        log.error("boot recovery: zone %s rebuild failed: %s", zid, result.error)


async def _recover_active(zid: str, zone: dict, current_pbf_mtime: float) -> None:
    """Recover an active zone — check if map files exist, rebuild if not."""
    zone_dir = f"{config.zones_dir}/{zid}"
    map_file = f"{zone_dir}/map.osrm.properties"

    # On ephemeral storage, files won't exist after restart → rebuild
    if not os.path.isfile(map_file):
        polygon = zone.get("polygon_geojson")
        if not polygon:
            log.warning("boot recovery: zone %s: map missing and no polygon in registry — marking failed", zid)
            await set_zone_status(zid, "failed", error="map missing, no polygon to rebuild")
            return
        linestrings = zone.get("linestrings_geojson") or None
        log.info("boot recovery: zone %s: map missing, rebuilding from registry", zid)
        await set_zone_status(zid, "building")
        result = await build_zone(zid, polygon, linestrings)
        if result.ok:
            await _start_after_rebuild(zid)
        else:
            log.error("boot recovery: zone %s rebuild failed: %s", zid, result.error)
        return

    # Map exists — verify hashes (ephemeral unlikely but possible)
    polygon_hash = zone.get("polygon_hash", "")
    linestrings_hash = zone.get("linestrings_hash", "")
    expected_pbf_mtime = zone.get("base_pbf_mtime", "")

    poly_file = f"{zone_dir}/polygon.geojson"
    if os.path.isfile(poly_file):
        import hashlib
        with open(poly_file, "rb") as f:
            content = f.read()
        actual_poly_hash = hashlib.sha256(content).hexdigest()
        if actual_poly_hash != polygon_hash:
            log.warning("boot recovery: zone %s: polygon hash mismatch — mark failed", zid)
            await set_zone_status(zid, "failed", error="content-hash mismatch")
            return

    # Start subprocesses
    try:
        await start_zone(zid)
        log.info("boot recovery: zone %s restarted successfully", zid)
    except Exception as exc:
        log.error("boot recovery: zone %s restart failed: %s", zid, exc)
        await set_zone_status(zid, "failed", error=str(exc))


async def _recover_built(zid: str, zone: dict, current_pbf_mtime: float) -> None:
    """Recover a built zone — rebuild if files missing, then start."""
    zone_dir = f"{config.zones_dir}/{zid}"
    map_file = f"{zone_dir}/map.osrm.properties"

    if not os.path.isfile(map_file):
        polygon = zone.get("polygon_geojson")
        if not polygon:
            log.warning("boot recovery: zone %s: map missing and no polygon — marking failed", zid)
            await set_zone_status(zid, "failed", error="map missing, no polygon to rebuild")
            return
        linestrings = zone.get("linestrings_geojson") or None
        log.info("boot recovery: zone %s: rebuilding from registry", zid)
        await set_zone_status(zid, "building")
        result = await build_zone(zid, polygon, linestrings)
        if result.ok:
            await _start_after_rebuild(zid)
        else:
            log.error("boot recovery: zone %s rebuild failed: %s", zid, result.error)
        return

    log.info("boot recovery: zone %s status='%s' — starting subprocesses", zid, zone.get("status"))
    try:
        await set_zone_status(zid, "starting")
        await start_zone(zid)
        log.info("boot recovery: zone %s recovered from built → active", zid)
    except Exception as exc:
        log.error("boot recovery: zone %s start failed: %s", zid, exc)
        await set_zone_status(zid, "failed", error=str(exc))


async def _start_after_rebuild(zid: str) -> None:
    """Start subprocesses after a successful rebuild."""
    await set_zone_status(zid, "starting")
    try:
        await start_zone(zid)
        log.info("boot recovery: zone %s rebuilt and started", zid)
    except Exception as exc:
        log.error("boot recovery: zone %s post-rebuild start failed: %s", zid, exc)
        await set_zone_status(zid, "failed", error=str(exc))


def _file_mtime(path: str) -> float:
    return os.path.getmtime(path) if os.path.isfile(path) else 0.0
