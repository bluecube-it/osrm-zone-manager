"""Build pipeline: osmium extract → reduce.py → osmium merge → osrm-extract/
partition/customize + subprocess health-wait.

Fase 4 — async, sequential steps, Redis status tracking.
"""

import asyncio
import os
import shutil
import stat
from dataclasses import dataclass, field
from typing import Optional

from app.config import config
from app.runtime.redis_client import (
    compute_hashes,
    get_redis,
    register_zone,
    reserve_port_pair,
    set_zone_status,
    release_port,
)
from app.utils.logger import get_logger

log = get_logger(__name__)


@dataclass
class BuildResult:
    zone_id: str
    ok: bool
    osrm_port: Optional[int] = None
    vroom_port: Optional[int] = None
    error: Optional[str] = None


def _file_mtime(path: str) -> float:
    return os.stat(path).st_mtime


async def _run(cmd: list, cwd: Optional[str] = None, timeout: int = 600) -> None:
    """Run subprocess, raise on non-zero exit."""
    log.debug("run: %s", " ".join(cmd))
    proc = await asyncio.create_subprocess_exec(
        *cmd,
        cwd=cwd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=timeout)
    if proc.returncode != 0:
        msg = stderr.decode(errors="replace").strip() or stdout.decode(errors="replace").strip()
        raise RuntimeError(f"{' '.join(cmd)} failed (rc={proc.returncode}): {msg[:500]}")
    if stdout:
        log.info("%s: %s", " ".join(cmd), stdout.decode(errors="replace").strip()[-400:])


async def build_zone(
    zone_id: str,
    polygon_geojson: dict,
    linestrings_geojson: Optional[dict] = None,
    force_rebuild: bool = False,
) -> BuildResult:
    """Build routing dataset for zone_id. Async — returns BuildResult.

    Idempotent: if zone already built with matching hashes, no-op.
    """
    zone_dir = f"{config.zones_dir}/{zone_id}"
    polygon_bytes = _geojson_bytes(polygon_geojson)
    linestrings_bytes = _geojson_bytes(linestrings_geojson) if linestrings_geojson else None

    base_pbf = config.base_pbf
    base_mtime = _file_mtime(base_pbf)
    polygon_hash, linestrings_hash, _ = compute_hashes(
        polygon_bytes, linestrings_bytes, base_mtime
    )

    # ── reuse check ──────────────────────────────────────────────────
    existing = await _get_zone_state(zone_id)
    if existing and existing.get("status") == "active":
        if not force_rebuild:
            if (existing.get("polygon_hash") == polygon_hash
                    and existing.get("linestrings_hash") == linestrings_hash
                    and existing.get("base_pbf_mtime") == str(base_mtime)):
                if os.path.isfile(f"{zone_dir}/map.osrm.properties"):
                    log.info("zone %s: hashes match, reusing", zone_id)
                    return BuildResult(zone_id=zone_id, ok=True,
                                       osrm_port=int(existing["osrm_port"]),
                                       vroom_port=int(existing["vroom_port"]))
        # hashes changed → rebuild below

    # ── allocate ports / reuse registration from create_zone ─────────
    existing = await _get_zone_state(zone_id)
    if existing and existing.get("status") == "building":
        osrm_port = int(existing.get("osrm_port", 0))
        vroom_port = int(existing.get("vroom_port", 0))
        if not (osrm_port and vroom_port):
            osrm_port, vroom_port = await reserve_port_pair()
    else:
        osrm_port, vroom_port = await reserve_port_pair()

    log.info("zone %s: allocated ports osrm=%d vroom=%d", zone_id, osrm_port, vroom_port)

    # ── register in Redis as building ────────────────────────────────
    if not existing or existing.get("status") != "building":
        try:
            await register_zone(zone_id, osrm_port, vroom_port,
                                polygon_hash, linestrings_hash, base_mtime)
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

    # ── step 1: mkdir + write polygon/linestrings ────────────────────
    os.makedirs(zone_dir, exist_ok=True)
    path_polygon = f"{zone_dir}/polygon.geojson"
    await _write_json(path_polygon, polygon_geojson)
    path_linestrings = None
    if linestrings_geojson:
        path_linestrings = f"{zone_dir}/linestrings.geojson"
        await _write_json(path_linestrings, linestrings_geojson)

    try:
        # ── step 2: osmium extract region ────────────────────────────
        region_pbf = f"{zone_dir}/region.osm.pbf"
        await _run([
            "osmium", "extract", "-p", path_polygon,
            base_pbf, "-o", region_pbf, "--overwrite"
        ])

        custom_pbf = f"{zone_dir}/custom_ways.pbf"
        if linestrings_bytes:
            # ── step 3a: reduce.py (synthetic linestrings) ───────────
            # Use absolute path so no PYTHONPATH needed
            reduce_script = os.path.join(
                os.path.dirname(__file__), "reduce.py"
            )
            await _run([
                "python3", reduce_script,
                base_pbf, path_linestrings, custom_pbf
            ], cwd=os.path.dirname(custom_pbf))
            # ── step 3b: osmium merge ────────────────────────────────
            combined = f"{zone_dir}/combined.osm.pbf"
            await _run([
                "osmium", "merge", region_pbf, custom_pbf,
                "-o", combined, "--overwrite"
            ])
        else:
            shutil.copy2(region_pbf, f"{zone_dir}/combined.osm.pbf")

        # ── step 4: osrm-extract + partition + customize ─────────────
        map_output = f"{zone_dir}/map"
        await _run([
            "osrm-extract", "-p", config.car_lua,
            "-o", map_output, "combined.osm.pbf"
        ], cwd=zone_dir)
        await _run(["osrm-partition", f"{zone_dir}/map.osrm"], cwd=zone_dir)
        await _run(["osrm-customize", f"{zone_dir}/map.osrm"], cwd=zone_dir)

        # ── step 5: clean up temp files (keep polygon/linestrings geojson) ─
        for f in ["region.osm.pbf", "custom_ways.pbf", "combined.osm.pbf"]:
            p = f"{zone_dir}/{f}"
            if os.path.isfile(p):
                os.remove(p)

        # ── step 6: copy vroom-express into zone ─────────────────────
        source_ve = config.vroom_express_dir
        dest_ve = f"{zone_dir}/vroom-express"
        if os.path.isdir(source_ve) and not os.path.isdir(dest_ve):
            try:
                shutil.copytree(source_ve, dest_ve, copy_function=os.link, dirs_exist_ok=True)
            except OSError:
                # hardlink fails across filesystems (e.g. tmpfs → volume)
                shutil.copytree(source_ve, dest_ve, copy_function=shutil.copy2, dirs_exist_ok=True)
            log.info("zone %s: vroom-express copied to %s", zone_id, dest_ve)

        # ── step 7: write per-zone vroom config ───────────────────────
        await _write_vroom_config(zone_dir, osrm_port, vroom_port)

        # ── update Redis ─────────────────────────────────────────────
        # status="built": files ready but subprocesses not yet started.
        # _start_after_build (zones.py) will transition to "active".
        await set_zone_status(
            zone_id, "built",
            error=None,
        )
        r = get_redis()
        await r.hset(f"osrm:zones:{zone_id}", mapping={
            "last_build_at": _iso_now(),
            "error": "",
        })

        log.info("zone %s: build complete", zone_id)
        return BuildResult(
            zone_id=zone_id, ok=True,
            osrm_port=osrm_port, vroom_port=vroom_port,
        )

    except Exception as exc:
        log.error("zone %s: build failed: %s", zone_id, exc)
        await set_zone_status(zone_id, "failed", error=str(exc))
        # Release allocated ports
        await release_port("osrm", osrm_port)
        await release_port("vroom", vroom_port)
        return BuildResult(zone_id=zone_id, ok=False, error=str(exc))


# ── helpers ─────────────────────────────────────────────────────────────────

async def _get_zone_state(zone_id: str) -> dict:
    r = get_redis()
    return await r.hgetall(f"osrm:zones:{zone_id}")


def _geojson_bytes(obj: dict) -> bytes:
    import json
    return json.dumps(obj, separators=(",", ":"), sort_keys=True).encode("utf-8")


async def _write_json(path: str, obj: dict) -> None:
    import json
    with open(path, "w") as f:
        json.dump(obj, f, separators=(",", ":"), sort_keys=True)


def _iso_now() -> str:
    from datetime import datetime, timezone
    return datetime.now(timezone.utc).isoformat()


async def _write_vroom_config(
    zone_dir: str, osrm_port: int, vroom_port: int
) -> None:
    """Generate per-zone config.yml into vroom-express subdir."""
    template = os.path.join(os.path.dirname(__file__), "../../config/vroom-config.template.yml")
    with open(template) as f:
        content = f.read()
    content = (
        content.replace("{{OSRM_PORT}}", str(osrm_port))
               .replace("{{VROOM_PORT}}", str(vroom_port))
    )
    dest = f"{zone_dir}/vroom-express/config.yml"
    with open(dest, "w") as f:
        f.write(content)
    log.debug("wrote vroom config: %s", dest)
