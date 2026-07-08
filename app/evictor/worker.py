"""Evictor: background async task scanning Redis for expired zones.

Fase 9 — TTL-based zone cleanup.
Skips `building` zones, uses per-zone file lock (Redis SETNX),
stops subprocesses + cleans disk + removes Redis record.
"""

import asyncio
from datetime import datetime, timezone, timedelta

from app.config import config
from app.runtime.redis_client import (
    delete_zone_record,
    get_redis,
    set_zone_status,
    release_port,
    get_zone,
    list_zones,
)
from app.runtime.supervisor import stop_zone, all_zone_ids
from app.utils.logger import get_logger

log = get_logger(__name__)

LOCK_PREFIX = "osrm:lock"


async def _evictor_loop() -> None:
    interval = config.evictor_interval_minutes * 60
    r = get_redis()

    while True:
        try:
            await _evict_tick(r)
        except Exception as exc:
            log.error("evictor error: %s", exc)
        await asyncio.sleep(interval)


async def _evict_tick(r) -> None:
    """One evictor scan pass."""
    zone_ids = await r.smembers("osrm:zones")
    now = datetime.now(timezone.utc)
    ttl = timedelta(days=config.zone_ttl_days)

    for zid in zone_ids:
        # Acquire per-zone lock (60s expiry, avoids race with supervisor)
        lock_key = f"{LOCK_PREFIX}:{zid}"
        locked = await r.set(lock_key, "1", ex=60, nx=True)
        if not locked:
            log.debug("evictor: zone %s locked by supervisor — skip", zid)
            continue

        try:
            zone = await get_zone(zid)
            if not zone:
                continue

            status = zone.get("status", "")
            if status == "active":
                # Parse last_access and check TTL
                last_access_str = zone.get("last_access", "")
                if not last_access_str:
                    continue
                try:
                    last_access = datetime.fromisoformat(last_access_str)
                except (ValueError, TypeError):
                    continue

                if now - last_access > ttl:
                    await _evict_zone(zid)
        finally:
            # Release lock
            await r.delete(lock_key)


async def _evict_zone(zone_id: str) -> None:
    """Stop, cleanup, delete an expired zone."""
    log.info("evictor: evicting zone %s (TTL expired)", zone_id)
    await set_zone_status(zone_id, "evicting")

    # Stop subprocesses
    try:
        await stop_zone(zone_id)
    except Exception as exc:
        log.warning("evictor: stop_zone failed for %s: %s", zone_id, exc)

    # Cleanup data dir
    import os, shutil
    zone_dir = f"{config.zones_dir}/{zone_id}"
    if os.path.isdir(zone_dir):
        shutil.rmtree(zone_dir, ignore_errors=True)

    # Remove stale Redis record + release ports
    await delete_zone_record(zone_id)

    log.info("evictor: zone %s evicted", zone_id)


async def start_evictor() -> asyncio.Task:
    log.info("evictor started (interval=%d min, ttl=%d days)",
             config.evictor_interval_minutes, config.zone_ttl_days)
    task = asyncio.create_task(_evictor_loop())
    return task
