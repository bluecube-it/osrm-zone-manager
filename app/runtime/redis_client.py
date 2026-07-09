"""JSON file registry — replaces Redis.

Single-file registry at /data/registry.json. asyncio.Lock guards all access
(Cloud Run concurrency=1, uvicorn workers=1). Write pattern: read-modify-write
under lock, atomic write via temp file + os.replace (works on GCS FUSE for
single-file replace, unlike Redis AOF rename fury).

Schema:
  {
    "zones": {
      "<zone_id>": {  # same fields as old Redis HASH
        "zone_id", "polygon_hash", "linestrings_hash", "base_pbf_mtime",
        "status", "osrm_port", "vroom_port", "osrm_pid", "vroom_pid",
        "created_at", "last_access", "last_build_at", "error"
      }
    }
  }

Port allocation lives implicitly in zone records — reserve_port_pair scans all
zones and skips ports already in use.
"""

import asyncio
import hashlib
import json
import os
from datetime import datetime, timezone
from typing import Dict, List, Optional

from app.config import config
from app.utils.logger import get_logger

log = get_logger(__name__)

# Module-level lock — guards all read/write to the JSON file + in-memory cache.
_lock = asyncio.Lock()
_cache: Optional[Dict] = None  # in-memory mirror of registry.json

REGISTRY_FILE = f"{config.data_dir}/registry.json"


async def _load() -> Dict:
    """Load registry from disk into cache (under lock). Caller must hold _lock."""
    global _cache
    if _cache is not None:
        return _cache
    if not os.path.isfile(REGISTRY_FILE):
        _cache = {"zones": {}}
        return _cache
    try:
        with open(REGISTRY_FILE, "r") as f:
            _cache = json.load(f)
        if "zones" not in _cache:
            _cache["zones"] = {}
    except (json.JSONDecodeError, OSError) as exc:
        # Corrupt registry — back it up and start fresh (data loss risk logged)
        log.error("registry corrupt, backing up and starting fresh: %s", exc)
        try:
            os.replace(REGISTRY_FILE, f"{REGISTRY_FILE}.corrupt")
        except OSError:
            pass
        _cache = {"zones": {}}
    return _cache


async def _dump() -> None:
    """Persist cache to disk (under lock). Atomic write via temp + replace."""
    global _cache
    if _cache is None:
        return
    tmp = f"{REGISTRY_FILE}.tmp"
    # Clean up stale tmp from previous crash
    try:
        if os.path.isfile(tmp):
            os.remove(tmp)
    except OSError:
        pass
    try:
        with open(tmp, "w") as f:
            json.dump(_cache, f, separators=(",", ":"), sort_keys=True)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, REGISTRY_FILE)
    except OSError as exc:
        log.error("registry write failed: %s", exc)
        raise


# ── zone registry ──────────────────────────────────────────────────────────


async def register_zone(
    zone_id: str,
    osrm_port: int,
    vroom_port: int,
    polygon_hash: str,
    linestrings_hash: str,
    base_pbf_mtime: float,
) -> None:
    now = iso_now()
    async with _lock:
        data = await _load()
        data["zones"][zone_id] = {
            "zone_id": zone_id,
            "polygon_hash": polygon_hash,
            "linestrings_hash": linestrings_hash,
            "base_pbf_mtime": str(base_pbf_mtime),
            "status": "building",
            "osrm_port": str(osrm_port),
            "vroom_port": str(vroom_port),
            "osrm_pid": "",
            "vroom_pid": "",
            "created_at": now,
            "last_access": now,
            "last_build_at": "",
            "error": "",
        }
        await _dump()


async def get_zone(zone_id: str) -> Optional[Dict[str, str]]:
    async with _lock:
        data = await _load()
        return data["zones"].get(zone_id)


async def list_zones() -> List[str]:
    async with _lock:
        data = await _load()
        return list(data["zones"].keys())


async def delete_zone_record(zone_id: str) -> None:
    async with _lock:
        data = await _load()
        data["zones"].pop(zone_id, None)
        await _dump()


_touch_dirty: set = set()
_touch_flush_task: Optional[asyncio.Task] = None


async def touch_zone(zone_id: str) -> None:
    """Update last_access timestamp in memory. Batched flush to disk every 30s.

    Avoids rewriting registry.json on every proxy request (GCS FUSE write
    cost). Evictor reads last_access from the in-memory cache which is always
    up-to-date. On container shutdown, lifespan stops the flush task which
    does a final write.
    """
    now = iso_now()
    async with _lock:
        data = await _load()
        zone = data["zones"].get(zone_id)
        if zone is not None:
            zone["last_access"] = now
            _touch_dirty.add(zone_id)
    _ensure_flush_task()


async def _flush_touch_loop() -> None:
    """Periodically persist dirty last_access timestamps to disk."""
    while True:
        await asyncio.sleep(30)
        await _flush_touch()


async def _flush_touch() -> None:
    """Write current cache to disk if there are dirty entries."""
    if not _touch_dirty:
        return
    async with _lock:
        await _dump()
    _touch_dirty.clear()


def _ensure_flush_task() -> None:
    global _touch_flush_task
    if _touch_flush_task is None or _touch_flush_task.done():
        try:
            _touch_flush_task = asyncio.create_task(_flush_touch_loop())
        except RuntimeError:
            pass  # no event loop yet


async def set_zone_status(
    zone_id: str, status: str, error: Optional[str] = None
) -> None:
    async with _lock:
        data = await _load()
        zone = data["zones"].get(zone_id)
        if zone is None:
            log.warning("set_zone_status: zone %s not in registry", zone_id)
            return
        zone["status"] = status
        if error is not None:
            zone["error"] = error if error.strip() else ""
        await _dump()


def _sha256_hex(content_bytes: bytes) -> str:
    return hashlib.sha256(content_bytes).hexdigest()


# ── port allocator ────────────────────────────────────────────────────────


async def reserve_port_pair() -> tuple:
    """Reserve one unused osrm + vroom port.

    Returns (osrm_port, vroom_port) or raises RuntimeError if exhausted.
    """
    osrm_start = config.osrm_port_start
    vroom_start = config.vroom_port_start

    async with _lock:
        data = await _load()
        used_osrm = {z.get("osrm_port") for z in data["zones"].values() if z.get("osrm_port")}
        used_vroom = {z.get("vroom_port") for z in data["zones"].values() if z.get("vroom_port")}

        for offset in range(1, 151):
            osrm_port = osrm_start + offset
            vroom_port = vroom_start + offset
            if str(osrm_port) in used_osrm:
                continue
            if str(vroom_port) in used_vroom:
                continue
            # Note: no dump here — caller will register_zone which writes
            return (osrm_port, vroom_port)

    raise RuntimeError("port pool exhausted — tried offset 1..150 (max zones ~150)")


async def release_port(kind: str, port: int) -> None:
    """No-op for JSON registry — ports are implicit in zone records.

    Kept for API compatibility. When a zone is deleted, its ports free
    automatically since reserve_port_pair scans zone records.
    """
    log.debug("release_port %s %d (no-op, implicit in zone records)", kind, port)


def compute_hashes(
    polygon_geojson: bytes, linestrings_geojson: Optional[bytes],
    base_pbf_mtime: float
) -> tuple:
    """Return (polygon_hash, linestrings_hash, base_pbf_mtime)."""
    ph = _sha256_hex(polygon_geojson)
    lh = _sha256_hex(linestrings_geojson) if linestrings_geojson else ""
    return (ph, lh, base_pbf_mtime)


async def set_zone_last_build(zone_id: str, ts: str) -> None:
    async with _lock:
        data = await _load()
        zone = data["zones"].get(zone_id)
        if zone is not None:
            zone["last_build_at"] = ts
            await _dump()


async def update_zone_fields(zone_id: str, fields: Dict[str, str]) -> None:
    """Bulk update zone fields (raw replacement for supervisor hset bypass)."""
    async with _lock:
        data = await _load()
        zone = data["zones"].get(zone_id)
        if zone is None:
            log.warning("update_zone_fields: zone %s not in registry", zone_id)
            return
        zone.update(fields)
        await _dump()


def iso_now() -> str:
    return datetime.now(timezone.utc).isoformat()
