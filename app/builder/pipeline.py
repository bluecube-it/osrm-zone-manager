"""Build pipeline: osmium extract → reduce.py → osmium merge → osrm-extract/
partition/customize + subprocess health-wait.

Async, sequential steps, JSON registry status tracking.
"""

import asyncio
import json
import os
import shutil
import stat
from dataclasses import dataclass, field
from typing import Optional

from app.config import config
from app.runtime.redis_client import (
    compute_hashes,
    get_zone,
    register_zone,
    reserve_port_pair,
    set_zone_status,
    set_zone_last_build,
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


def _write_file_sync(path: str, content: str) -> None:
    """Write string content to file (for use in asyncio.to_thread)."""
    with open(path, "w") as f:
        f.write(content)


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
    Build happens in local tmpfs staging dir, final files moved to GCS at end.
    """
    zone_dir = f"{config.zones_dir}/{zone_id}"
    # ── local staging dir (tmpfs, fast random write, no GCS 503) ──────
    staging_dir = f"{config.staging_dir}/{zone_id}"
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

    # ── register in registry as building ──────────────────────────────
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

    # ── step 1: mkdir staging + write polygon/linestrings ────────────
    # Write to local tmpfs first; copy final files to GCS at end.
    os.makedirs(staging_dir, exist_ok=True)
    # Also ensure zone_dir exists for reuse check file reads.
    os.makedirs(zone_dir, exist_ok=True)
    path_polygon = f"{zone_dir}/polygon.geojson"
    await asyncio.to_thread(_write_file_sync, path_polygon,
                            json.dumps(polygon_geojson, separators=(",", ":"), sort_keys=True))
    path_linestrings = None
    if linestrings_geojson:
        path_linestrings = f"{zone_dir}/linestrings.geojson"
        await asyncio.to_thread(_write_file_sync, path_linestrings,
                                json.dumps(linestrings_geojson, separators=(",", ":"), sort_keys=True))

    try:
        # ── step 2: osmium extract region (in staging) ───────────────
        region_pbf = f"{staging_dir}/region.osm.pbf"
        await _run([
            "osmium", "extract", "-p", path_polygon,
            base_pbf, "-o", region_pbf, "--overwrite"
        ])

        custom_pbf = f"{staging_dir}/custom_ways.pbf"
        if linestrings_bytes:
            # ── step 3a: reduce.py (synthetic linestrings) ───────────
            reduce_script = os.path.join(
                os.path.dirname(__file__), "reduce.py"
            )
            await _run([
                "python3", reduce_script,
                base_pbf, path_linestrings, custom_pbf
            ], cwd=staging_dir)
            # ── step 3b: osmium merge ────────────────────────────────
            combined = f"{staging_dir}/combined.osm.pbf"
            await _run([
                "osmium", "merge", region_pbf, custom_pbf,
                "-o", combined, "--overwrite"
            ])
        else:
            shutil.copy2(region_pbf, f"{staging_dir}/combined.osm.pbf")

        # ── step 4: osrm-extract + partition + customize (in staging) ──
        map_output = f"{staging_dir}/map"
        await _run([
            "osrm-extract", "-p", config.car_lua,
            "-o", map_output, "combined.osm.pbf"
        ], cwd=staging_dir)
        await _run(["osrm-partition", f"{staging_dir}/map.osrm"], cwd=staging_dir)
        await _run(["osrm-customize", f"{staging_dir}/map.osrm"], cwd=staging_dir)

        # ── step 5: clean up temp PBF files in staging ─────────────────
        def _cleanup_tmp():
            for f in ["region.osm.pbf", "custom_ways.pbf", "combined.osm.pbf"]:
                p = f"{staging_dir}/{f}"
                if os.path.isfile(p):
                    os.remove(p)
        await asyncio.to_thread(_cleanup_tmp)

        # ── step 6: vroom-express lives globally at /vroom-express (read-only).
        # Per-zone needs config.yml + healthchecks/vroom_custom_matrix.json.
        # No node_modules, no src copy — node resolves via NODE_PATH.
        vroom_config_dir = f"{staging_dir}/vroom-express"
        source_ve = config.vroom_express_dir
        def _ensure_vroom_dir():
            if os.path.isdir(vroom_config_dir):
                shutil.rmtree(vroom_config_dir, ignore_errors=True)
            os.makedirs(f"{vroom_config_dir}/healthchecks", exist_ok=True)
            # Copy healthchecks from global vroom-express
            hc_src = f"{source_ve}/healthchecks/vroom_custom_matrix.json"
            if os.path.isfile(hc_src):
                shutil.copy2(hc_src, f"{vroom_config_dir}/healthchecks/")
        await asyncio.to_thread(_ensure_vroom_dir)

        # ── step 7: write per-zone vroom config (in staging) ───────────
        await _write_vroom_config(staging_dir, osrm_port, vroom_port)

        # ── step 8: move final files to GCS (zone_dir) ─────────────────
        # This is the ONLY sync I/O to GCS FUSE during build.
        log.info("zone %s: moving files from staging to GCS...", zone_id)
        def _move_to_gcs():
            moved = []
            # Clean stale vroom-express from previous deploys before writing fresh
            old_ve = f"{zone_dir}/vroom-express"
            if os.path.isdir(old_ve):
                shutil.rmtree(old_ve, ignore_errors=True)
            for f in os.listdir(staging_dir):
                src = f"{staging_dir}/{f}"
                dst = f"{zone_dir}/{f}"
                if os.path.isfile(src):
                    shutil.copy2(src, dst)
                    moved.append(f)
                elif os.path.isdir(src):
                    shutil.copytree(src, dst, dirs_exist_ok=True,
                                    copy_function=shutil.copy2)
                    moved.append(f"{f}/")
            # Cleanup staging
            shutil.rmtree(staging_dir, ignore_errors=True)
            return moved
        moved_files = await asyncio.to_thread(_move_to_gcs)
        log.info("zone %s: moved %d items to GCS: %s", zone_id, len(moved_files), moved_files)

        # ── update registry ───────────────────────────────────────────
        # status="built": files ready but subprocesses not yet started.
        # _start_after_build (zones.py) will transition to "active".
        await set_zone_status(
            zone_id, "built",
            error=None,
        )
        await set_zone_last_build(zone_id, _iso_now())

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
    return await get_zone(zone_id) or {}


def _geojson_bytes(obj: dict) -> bytes:
    import json
    return json.dumps(obj, separators=(",", ":"), sort_keys=True).encode("utf-8")


async def _write_json(path: str, obj: dict) -> None:
    import json
    def _write():
        with open(path, "w") as f:
            json.dump(obj, f, separators=(",", ":"), sort_keys=True)
    await asyncio.to_thread(_write)


def _iso_now() -> str:
    from datetime import datetime, timezone
    return datetime.now(timezone.utc).isoformat()


async def _write_vroom_config(
    zone_dir: str, osrm_port: int, vroom_port: int
) -> None:
    """Generate per-zone config.yml into vroom-express subdir."""
    template = os.path.join(os.path.dirname(__file__), "../../config/vroom-config.template.yml")
    def _write():
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
    await asyncio.to_thread(_write)
