"""Process supervisor: manage osrm-routed + vroom-express per zone.

Fase 5 — start/stop/restart with health-wait loops, per-zone vroom config,
max 3 restart retries before marking zone degraded.
"""

import asyncio
import os
from dataclasses import dataclass
from typing import Dict, Optional, Set

from app.runtime.redis_client import delete_zone_record

from app.config import config
from app.runtime.redis_client import get_redis, release_port, set_zone_status
from app.utils.logger import get_logger

log = get_logger(__name__)


@dataclass
class _Proc:
    zone_id: str
    osrm: Optional[asyncio.subprocess.Process] = None
    vroom: Optional[asyncio.subprocess.Process] = None
    osrm_port: int = 0
    vroom_port: int = 0
    osrm_pid: int = 0
    vroom_pid: int = 0
    retries: int = 0
    healthy: bool = True


_registry: Dict[str, _Proc] = {}
_health_checker_task: Optional[asyncio.Task] = None


async def start_zone(zone_id: str) -> None:
    """Start osrm-routed + vroom-express for zone_id.

    Reads osrm/vroom ports from Redis; assumes zone is "active".
    Allocates ports only during build, not during recovery.
    """
    r = get_redis()
    zone = await r.hgetall(f"osrm:zones:{zone_id}")

    if zone_id in _registry:
        log.info("zone %s: already started, skipping", zone_id)
        return

    osrm_port = int(zone.get("osrm_port", config.osrm_port_start + 1))
    vroom_port = int(zone.get("vroom_port", config.vroom_port_start + 1))

    proc = _Proc(zone_id=zone_id, osrm_port=osrm_port, vroom_port=vroom_port)

    map_base = f"{config.zones_dir}/{zone_id}/map"
    await _start_osrm(proc, map_base, osrm_port)
    await _start_vroom(proc, zone_id, osrm_port, vroom_port)

    if proc.healthy:
        await r.hset(f"osrm:zones:{zone_id}", mapping={
            "osrm_port": str(osrm_port),
            "vroom_port": str(vroom_port),
            "osrm_pid": str(proc.osrm_pid),
            "vroom_pid": str(proc.vroom_pid),
            "status": "active",
            "error": "",
        })
        log.info("zone %s started: osrm=%d(pid=%d) vroom=%d(pid=%d)",
                 zone_id, osrm_port, proc.osrm_pid, vroom_port, proc.vroom_pid)
        _registry[zone_id] = proc
    else:
        await _kill_proc(proc)
        await set_zone_status(zone_id, "failed", error="startup timeout")
        await release_port("osrm", osrm_port)
        await release_port("vroom", vroom_port)
        log.error("zone %s: startup failed, ports %d/%d released",
                  zone_id, osrm_port, vroom_port)


async def stop_zone(zone_id: str) -> None:
    """Kill subprocesses, release ports. Does NOT delete Redis record."""
    proc = _registry.pop(zone_id, None)
    if not proc:
        log.warning("zone %s: stop called but not in registry", zone_id)
        return

    await _kill_proc(proc)

    await release_port("osrm", proc.osrm_port)
    await release_port("vroom", proc.vroom_port)

    log.info("zone %s: stopped (ports %d/%d released)",
             zone_id, proc.osrm_port, proc.vroom_port)


async def stop_all_zones() -> None:
    ids = list(_registry.keys())
    for zid in ids:
        await stop_zone(zid)
    global _health_checker_task
    if _health_checker_task:
        _health_checker_task.cancel()
        try:
            await _health_checker_task
        except asyncio.CancelledError:
            pass
    # Close httpx client
    global _http_client_instance
    if _http_client_instance:
        await _http_client_instance.aclose()
        _http_client_instance = None


def all_zone_ids() -> Set[str]:
    return set(_registry.keys())


# ── subprocess spawning ────────────────────────────────────────────────────

async def _start_osrm(proc: _Proc, map_base: str, port: int) -> None:
    """Spawn osrm-routed — loopback-only, MLD algorithm, mmap."""
    args = [
        "osrm-routed", "--algorithm", "mld",
        "--ip", "127.0.0.1", "--port", str(port), str(map_base),
    ]
    if config.osrm_mmap:
        args.append("--mmap")
    proc.osrm = await asyncio.create_subprocess_exec(
        *args,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    proc.osrm_pid = proc.osrm.pid

    ok = await _wait_health(
        f"http://127.0.0.1:{port}/route/v1/driving/0,0;0,0", timeout=120,
    )
    proc.healthy = ok
    if not ok:
        log.error("zone %s: osrm-routed timeout on port %d", proc.zone_id, port)
        await _kill_single(proc.osrm, "osrm", proc.osrm_pid, wait=2)
    else:
        log.info("zone %s: osrm-routed healthy on port %d (pid=%d)",
                 proc.zone_id, port, proc.osrm_pid)


async def _start_vroom(proc: _Proc, zone_id: str, osrm_port: int, vroom_port: int) -> None:
    """Spawn vroom-express from zone/ subdir where config.yml + healthchecks/ live."""
    vroom_dir = f"{config.zones_dir}/{zone_id}/vroom-express"
    env = os.environ.copy()
    env["NODE_PATH"] = f"{config.vroom_express_dir}/node_modules"
    proc.vroom = await asyncio.create_subprocess_exec(
        "node", f"{config.vroom_express_dir}/src/index.js",
        cwd=vroom_dir,
        env=env,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    proc.vroom_pid = proc.vroom.pid

    ok = await _wait_health(
        f"http://127.0.0.1:{vroom_port}/health", timeout=60,
    )
    proc.healthy = proc.healthy and ok
    if not ok:
        log.error("zone %s: vroom-express timeout on port %d", proc.zone_id, vroom_port)
        await _kill_single(proc.vroom, "vroom", proc.vroom_pid, wait=2)
    else:
        log.info("zone %s: vroom-express healthy on port %d (pid=%d)",
                 proc.zone_id, vroom_port, proc.vroom_pid)


# ── health waiter ──────────────────────────────────────────────────────────

async def _wait_health(url: str, timeout: int) -> bool:
    elapsed = 0
    while elapsed < timeout:
        try:
            async with asyncio.timeout(3):
                client = await _get_http_client()
                resp = await client.get(url)
                # 400 = NoSegment at 0,0 — means OSRM is up and responding
                if resp.status_code < 500 or resp.status_code == 400:
                    return True
        except (asyncio.TimeoutError, Exception):
            pass
        await asyncio.sleep(2)
        elapsed += 2
    return False


# ── process management ─────────────────────────────────────────────────────

async def _kill_proc(proc: _Proc) -> None:
    await _kill_single(proc.osrm, f"osrm({proc.zone_id})", proc.osrm_pid, wait=5)
    await _kill_single(proc.vroom, f"vroom({proc.zone_id})", proc.vroom_pid, wait=5)


async def _kill_single(p: Optional[asyncio.subprocess.Process], name: str,
                       pid: int, wait: int = 5) -> None:
    if p is None or p.returncode is not None:
        return
    try:
        p.terminate()
        await asyncio.wait_for(p.wait(), timeout=wait)
    except (asyncio.TimeoutError, Exception):
        try:
            p.kill()
            await asyncio.wait_for(p.wait(), timeout=3)
        except Exception:
            pass
    log.debug("killed %s (pid=%d)", name, pid)


# ── health checker ─────────────────────────────────────────────────────────

async def _health_check_loop() -> None:
    """Ping active zones every 30s; retry dead ones (max 3), mark degraded."""
    while True:
        await asyncio.sleep(30)
        for zone_id, proc in list(_registry.items()):
            try:
                await _check_one(zone_id, proc)
            except Exception as exc:
                log.warning("zone %s: health check error: %s", zone_id, exc)


async def _check_one(zone_id: str, proc: _Proc) -> None:
    max_retries = 3
    url = f"http://127.0.0.1:{proc.osrm_port}/route/v1/driving/0,0;0,0"
    vroom_url = f"http://127.0.0.1:{proc.vroom_port}/health"

    osrm_ok = await _ping(url)
    vroom_ok = await _ping(vroom_url)

    if osrm_ok and vroom_ok:
        if not proc.healthy:
            proc.healthy = True
            proc.retries = 0
            await set_zone_status(zone_id, "active")
            log.info("zone %s: recovered to active", zone_id)
        return

    proc.retries += 1
    if proc.retries > max_retries:
        await set_zone_status(
            zone_id, "degraded",
            error=f"unhealthy after {proc.retries} retries"
        )
        log.warning("zone %s: marked degraded (%d retries)", zone_id, proc.retries)
        return

    log.warning("zone %s: unhealthy, restart attempt %d/%d",
                zone_id, proc.retries, max_retries)
    await _kill_proc(proc)
    proc.osrm = None
    proc.vroom = None

    map_base = f"{config.zones_dir}/{zone_id}/map"
    await _start_osrm(proc, map_base, proc.osrm_port)
    await _start_vroom(proc, zone_id, proc.osrm_port, proc.vroom_port)


async def _ping(url: str) -> bool:
    try:
        async with asyncio.timeout(3):
            client = await _get_http_client()
            resp = await client.get(url)
            return resp.status_code < 500 or resp.status_code == 400
    except Exception:
        return False


# ── HTTP client singleton ──────────────────────────────────────────────────

import httpx
_http_client_instance: Optional[httpx.AsyncClient] = None


async def _get_http_client() -> httpx.AsyncClient:
    global _http_client_instance
    if _http_client_instance is None:
        _http_client_instance = httpx.AsyncClient(timeout=5)
    return _http_client_instance


async def start_health_checker() -> asyncio.Task:
    global _health_checker_task
    if _health_checker_task and not _health_checker_task.done():
        return _health_checker_task
    _health_checker_task = asyncio.create_task(_health_check_loop())
    return _health_checker_task
