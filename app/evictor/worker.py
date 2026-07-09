"""Evictor: background async task scanning registry for expired zones.

TTL-based zone cleanup. Skips `building` zones, uses per-zone in-memory lock,
stops subprocesses + cleans disk + removes registry record.
"""

import asyncio
from datetime import datetime, timezone, timedelta

from app.config import config
from app.runtime.redis_client import (
    delete_zone_record,
    set_zone_status,
    get_zone,
    list_zones,
)
from app.runtime.supervisor import stop_zone, all_zone_ids
from app.utils.logger import get_logger

log = get_logger(__name__)

# Per-zone in-memory lock — replaces Redis SETNX. concurrency=1 / workers=1.
_locks: dict = {}


def _get_lock(zone_id: str) -> asyncio.Lock:
    """Get or create the in-memory lock for a zone."""
    lock = _locks.get(zone_id)
    if lock is None:
        lock = asyncio.Lock()
        _locks[zone_id] = lock
    return lock


async def _evictor_loop() -> None:
    interval = config.evictor_interval_minutes * 60

    while True:
        try:
            await _evict_tick()
        except Exception as exc:
            log.error("evictor error: %s", exc)
        await asyncio.sleep(interval)


async def _evict_tick() -> None:
    """One evictor scan pass."""
    zone_ids = await list_zones()
    now = datetime.now(timezone.utc)
    ttl = timedelta(days=config.zone_ttl_days)

    for zid in zone_ids:
        lock = _get_lock(zid)
        if lock.locked():
            log.debug("evictor: zone %s locked — skip", zid)
            continue
        async with lock:
            zone = await get_zone(zid)
            if not zone:
                _locks.pop(zid, None)
                continue

            status = zone.get("status", "")
            if status == "active":
                last_access_str = zone.get("last_access", "")
                if not last_access_str:
                    _locks.pop(zid, None)
                    continue
                try:
                    last_access = datetime.fromisoformat(last_access_str)
                except (ValueError, TypeError):
                    _locks.pop(zid, None)
                    continue

                if now - last_access > ttl:
                    await _evict_zone(zid)

            _locks.pop(zid, None)


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

    # Remove registry record + release ports (implicit)
    await delete_zone_record(zone_id)

    log.info("evictor: zone %s evicted", zone_id)


async def start_evictor() -> asyncio.Task:
    log.info("evictor started (interval=%d min, ttl=%d days)",
             config.evictor_interval_minutes, config.zone_ttl_days)
    task = asyncio.create_task(_evictor_loop())
    return task
