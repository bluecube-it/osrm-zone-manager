"""Async Redis client — singleton pool + helpers.

Redis HASH: osrm:zones:<zone_id>
Redis SET:  osrm:zones          — all zone_ids
Redis SET:  osrm:ports:osrm     — used osrm ports
Redis SET:  osrm:ports:vroom    — used vroom ports
"""

import hashlib
from datetime import datetime, timezone
from typing import Dict, List, Optional

import redis.asyncio as redis

from app.config import config


_pool = None


def get_redis() -> redis.Redis:
    global _pool
    if _pool is None:
        _pool = redis.ConnectionPool(
            host=config.redis_host, port=config.redis_port, decode_responses=True
        )
    return redis.Redis(connection_pool=_pool)


# ── zone registry ──────────────────────────────────────────────────────────

ZONE_KEY = "osrm:zones"      # SET of zone IDs
PORT_OSRM_KEY = "osrm:ports:osrm"
PORT_VROOM_KEY = "osrm:ports:vroom"


async def register_zone(
    zone_id: str,
    osrm_port: int,
    vroom_port: int,
    polygon_hash: str,
    linestrings_hash: str,
    base_pbf_mtime: float,
) -> None:
    now = datetime.now(timezone.utc).isoformat()
    r = get_redis()
    pipe = r.pipeline()
    pipe.hset(f"osrm:zones:{zone_id}", mapping={
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
    })
    pipe.sadd(ZONE_KEY, zone_id)

    # Reserve port pair
    pipe.sadd(PORT_OSRM_KEY, str(osrm_port))
    pipe.sadd(PORT_VROOM_KEY, str(vroom_port))

    await pipe.execute()


async def get_zone(zone_id: str) -> Optional[Dict[str, str]]:
    r = get_redis()
    return await r.hgetall(f"osrm:zones:{zone_id}")


async def list_zones() -> List[str]:
    r = get_redis()
    return await r.smembers(ZONE_KEY)


async def delete_zone_record(zone_id: str) -> None:
    r = get_redis()
    zone = await get_zone(zone_id)
    pipe = r.pipeline()
    # Release ports
    if zone and zone.get("osrm_port"):
        pipe.srem(PORT_OSRM_KEY, zone["osrm_port"])
    if zone and zone.get("vroom_port"):
        pipe.srem(PORT_VROOM_KEY, zone["vroom_port"])
    pipe.delete(f"osrm:zones:{zone_id}")
    pipe.srem(ZONE_KEY, zone_id)
    await pipe.execute()


async def touch_zone(zone_id: str) -> None:
    """Update last_access timestamp."""
    now = datetime.now(timezone.utc).isoformat()
    r = get_redis()
    await r.hset(f"osrm:zones:{zone_id}", "last_access", now)


async def set_zone_status(
    zone_id: str, status: str, error: Optional[str] = None
) -> None:
    r = get_redis()
    data: Dict[str, str] = {"status": status}
    if error is not None:
        # Empty string is "truthy falsy" in Redis — use sentinel ""
        if error.strip():
            data["error"] = error
        else:
            data["error"] = ""
    await r.hset(f"osrm:zones:{zone_id}", mapping=data)


def _sha256_hex(content_bytes: bytes) -> str:
    return hashlib.sha256(content_bytes).hexdigest()


# ── port allocator ────────────────────────────────────────────────────────

async def reserve_port_pair() -> tuple:
    """Reserve one unused osrm + vroom port from Redis sets.
    Returns (osrm_port, vroom_port) or raises RuntimeError if all ports
    in the configured range are exhausted."""
    osrm_start = config.osrm_port_start  # 5000
    vroom_start = config.vroom_port_start  # 3000

    r = get_redis()
    used_osrm = await r.smembers(PORT_OSRM_KEY)
    used_vroom = await r.smembers(PORT_VROOM_KEY)

    # Try 150 ports per service
    for offset in range(1, 151):
        osrm_port = osrm_start + offset
        vroom_port = vroom_start + offset
        if str(osrm_port) in used_osrm:
            continue
        if str(vroom_port) in used_vroom:
            continue
        await r.sadd(PORT_OSRM_KEY, str(osrm_port))
        await r.sadd(PORT_VROOM_KEY, str(vroom_port))
        return (osrm_port, vroom_port)

    raise RuntimeError(
        f"port pool exhausted — tried offset 1..150 (max zones ~150)"
    )


def compute_hashes(
    polygon_geojson: bytes, linestrings_geojson: Optional[bytes],
    base_pbf_mtime: float
) -> tuple:
    """Return (polygon_hash, linestrings_hash, base_pbf_mtime)."""
    ph = _sha256_hex(polygon_geojson)
    lh = _sha256_hex(linestrings_geojson) if linestrings_geojson else ""
    return (ph, lh, base_pbf_mtime)


async def release_port(kind: str, port: int) -> None:
    """Release a port from the Redis allocation set."""
    r = get_redis()
    key = PORT_OSRM_KEY if kind == "osrm" else PORT_VROOM_KEY
    await r.srem(key, str(port))


async def set_zone_last_build(zone_id: str, ts: str) -> None:
    r = get_redis()
    await r.hset(f"osrm:zones:{zone_id}", "last_build_at", ts)


def iso_now() -> str:
    from datetime import datetime, timezone
    return datetime.now(timezone.utc).isoformat()
