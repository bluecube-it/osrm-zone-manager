"""Zone management endpoints: POST/GET/GET:id/DELETE /zones.

CRUD backed by JSON file registry. Build triggers async, returns
zone_id immediately with status "building".
"""

import asyncio
from typing import Dict, Optional

from fastapi import APIRouter, HTTPException

from app.config import config
from app.runtime.registry_store import (
    compute_hashes,
    get_zone,
    list_zones,
    delete_zone_record,
    register_zone,
    release_port,
    reserve_port_pair,
    set_zone_status,
)
from app.runtime.supervisor import start_zone, stop_zone, all_zone_ids
from app.builder.pipeline import build_zone
from app.utils.pbf import ensure_base_pbf
from app.utils.logger import get_logger

log = get_logger(__name__)

router = APIRouter(prefix="/zones", tags=["zones"])

_build_tasks: Dict[str, asyncio.Task] = {}
_start_tasks: Dict[str, asyncio.Task] = {}


@router.post("")
async def create_zone(polygon: dict, linestrings: Optional[dict] = None):
    """Create a zone: validate, download PBF if needed, trigger async build.

    Validates polygon (must have "type":"Feature" geometry), caps active zones,
    downloads base PBF if missing, then spawns `build_zone` as background task.
    Returns immediately with {zone_id, status: "building"}.
    """
    # ── cap check ─────────────────────────────────────────────────────
    active_count = len(await list_zones())
    if active_count >= config.max_active_zones:
        raise HTTPException(
            status_code=429,
            detail=f"max active zones ({config.max_active_zones}) reached; "
                   f"evict or delete before creating new zone",
        )

    # ── generate zone_id ─────────────────────────────────────────────
    import json, hashlib, os

    base_pbf = config.base_pbf
    base_mtime = os.path.getmtime(base_pbf) if os.path.isfile(base_pbf) else 0
    polygon_bytes = json.dumps(polygon, separators=(",", ":"), sort_keys=True).encode()
    linestrings_bytes = None
    if linestrings:
        linestrings_bytes = json.dumps(linestrings, separators=(",", ":"), sort_keys=True).encode()
    computed_ph, computed_lh, _ = compute_hashes(
        polygon_bytes, linestrings_bytes, base_mtime
    )
    # zone_id derived from polygon + linestrings content (not polygon only),
    # so same polygon with different linestrings yields different zone_id
    zone_id_source = polygon_bytes
    if linestrings_bytes:
        zone_id_source += b"|" + linestrings_bytes
    zone_id = hashlib.sha256(zone_id_source).hexdigest()[:12]

    # idempotent: matching zone found?
    all_zones = await list_zones()
    for zid in all_zones:
        rec = await get_zone(zid)
        if not rec:
            continue
        stored_ph = rec.get("polygon_hash", "")
        stored_lh = rec.get("linestrings_hash", "")
        stored_pbf = rec.get("base_pbf_mtime", "")
        status = rec.get("status", "")
        if stored_ph == computed_ph and stored_lh == computed_lh and str(base_mtime) == stored_pbf:
            map_file = f"{config.zones_dir}/{zid}/map.osrm.properties"
            if status in ("active", "degraded") and os.path.isfile(map_file):
                if zid not in all_zone_ids():
                    log.warning(
                        "zone %s: record %s but processes not running, rebuilding", zid, status
                    )
                    old_osrm = int(rec.get("osrm_port", 0))
                    old_vroom = int(rec.get("vroom_port", 0))
                    if old_osrm:
                        await release_port("osrm", old_osrm)
                    if old_vroom:
                        await release_port("vroom", old_vroom)
                    await set_zone_status(zid, "degraded")
                else:
                    log.info("zone reuse: matching hashes for polygon_hash=%s", computed_ph[:12])
                    return {
                        "zone_id": zid,
                        "status": status,
                        "osrm_port": int(rec.get("osrm_port", 0)),
                        "vroom_port": int(rec.get("vroom_port", 0)),
                        "message": "zone already active with same content — reusing",
                    }
            if status in ("building", "built", "starting"):
                log.info("zone in-progress: matching hashes, status=%s", status)
                raise HTTPException(
                    status_code=409,
                    detail=f"zone {zid} with same content is {status} — poll GET /zones/{zid}",
                )

    # ── ensure base PBF ──────────────────────────────────────────────
    try:
        await ensure_base_pbf()
    except RuntimeError as exc:
        raise HTTPException(status_code=503, detail=str(exc))

    # ── reserve ports + register in registry before build task starts ──
    osrm_port, vroom_port = await reserve_port_pair()
    try:
        await register_zone(
            zone_id, osrm_port, vroom_port, computed_ph, computed_lh, base_mtime,
            polygon_geojson=polygon,
            linestrings_geojson=linestrings,
        )
        await set_zone_status(zone_id, "building")
    except Exception as exc:
        try:
            await release_port("osrm", osrm_port)
        except Exception as release_exc:
            log.warning("zone %s: release osrm port %d failed: %s", zone_id, osrm_port, release_exc)
        try:
            await release_port("vroom", vroom_port)
        except Exception as release_exc:
            log.warning("zone %s: release vroom port %d failed: %s", zone_id, vroom_port, release_exc)
        raise exc

    # ── trigger async build ──────────────────────────────────────────
    task = asyncio.create_task(build_zone(zone_id, polygon, linestrings))
    _build_tasks[zone_id] = task
    task.add_done_callback(lambda t, zid=zone_id: _build_tasks.pop(zid, None))
    task.add_done_callback(lambda t: _log_build_done(zone_id, t))

    return {
        "zone_id": zone_id,
        "status": "building",
        "osrm_port": osrm_port,
        "vroom_port": vroom_port,
        "message": "build started — poll GET /zones/{id} for status",
    }


def _log_build_done(zone_id: str, task: asyncio.Task) -> None:
    try:
        result = task.result()
        if result.ok:
            log.info("zone %s: build succeeded", zone_id)
            start_task = asyncio.create_task(_start_after_build(zone_id))
            _start_tasks[zone_id] = start_task
            start_task.add_done_callback(lambda t, zid=zone_id: _start_tasks.pop(zid, None))
        else:
            log.error("zone %s: build failed: %s", zone_id, result.error)
    except asyncio.CancelledError:
        pass
    except Exception as exc:
        log.error("zone %s: unhandled build exception: %s", zone_id, exc)


async def _start_after_build(zone_id: str) -> None:
    """Spawn supervisor.start_zone after a successful build.

    Status transitions: built → starting → active | failed.
    Catches all exceptions so fire-and-forget task never dies silently.
    """
    await set_zone_status(zone_id, "starting")
    try:
        await start_zone(zone_id)
    except Exception as exc:
        log.exception("zone %s: post-build start failed", zone_id)
        await set_zone_status(zone_id, "failed", error=f"post-build start: {exc}")


@router.get("")
async def list_zones_endpoint():
    """Return all zones with metadata, no body.

    Sorted by last_access desc (most recently used first)."""
    zone_ids = await list_zones()

    zones = []
    for zid in zone_ids:
        zone = await get_zone(zid)
        zones.append({
            "zone_id": zone.get("zone_id"),
            "status": zone.get("status"),
            "osrm_port": int(zone.get("osrm_port", 0)),
            "vroom_port": int(zone.get("vroom_port", 0)),
            "osrm_pid": zone.get("osrm_pid"),
            "vroom_pid": zone.get("vroom_pid"),
            "created_at": zone.get("created_at"),
            "last_access": zone.get("last_access"),
            "last_build_at": zone.get("last_build_at"),
            "error": zone.get("error"),
        })
    zones.sort(key=lambda z: z.get("last_access") or "", reverse=True)
    return {"zones": zones}


@router.get("/{zone_id}")
async def get_zone_endpoint(zone_id: str):
    """Return zone metadata."""
    # Check if it's in the process registry too
    proc_status = None
    if zone_id in all_zone_ids():
        proc_status = "running"

    zone = await get_zone(zone_id)
    if not zone:
        raise HTTPException(status_code=404, detail=f"zone {zone_id} not found")

    return {
        "zone_id": zone.get("zone_id"),
        "status": zone.get("status"),
        "osrm_port": int(zone.get("osrm_port", 0)),
        "vroom_port": int(zone.get("vroom_port", 0)),
        "osrm_pid": zone.get("osrm_pid"),
        "vroom_pid": zone.get("vroom_pid"),
        "polygon_hash": zone.get("polygon_hash"),
        "linestrings_hash": zone.get("linestrings_hash"),
        "base_pbf_mtime": zone.get("base_pbf_mtime"),
        "created_at": zone.get("created_at"),
        "last_access": zone.get("last_access"),
        "last_build_at": zone.get("last_build_at"),
        "error": zone.get("error"),
        "process": proc_status,
    }


@router.delete("/{zone_id}")
async def delete_zone_endpoint(zone_id: str):
    """Stop subprocesses + cleanup data + delete registry record."""
    zone = await get_zone(zone_id)
    if not zone:
        raise HTTPException(status_code=404, detail=f"zone {zone_id} not found")

    # Cancel in-flight build task before touching files
    if zone.get("status") == "building":
        task = _build_tasks.pop(zone_id, None)
        if task and not task.done():
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass

    # Cancel in-flight start task (built / starting)
    if zone.get("status") in ("built", "starting"):
        task = _start_tasks.pop(zone_id, None)
        if task and not task.done():
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass

    # Stop running processes first
    if zone.get("status") in ("active", "degraded", "starting"):
        await stop_zone(zone_id)

    # Remove from data dir
    import os, shutil
    zone_dir = f"{config.zones_dir}/{zone_id}"
    if os.path.isdir(zone_dir):
        shutil.rmtree(zone_dir, ignore_errors=True)

    # Delete registry record
    await delete_zone_record(zone_id)

    log.info("zone %s: deleted", zone_id)
    return {
        "zone_id": zone_id,
        "status": "deleted",
    }
