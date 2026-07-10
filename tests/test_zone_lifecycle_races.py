"""Contract tests for zone lifecycle race conditions.

Pure Python — no Docker, no Redis, no real subprocesses.
Tests exercise invariants using the JSON file registry with temp file.
"""

import asyncio
import hashlib
import json
import os
from unittest.mock import patch, AsyncMock

import pytest

from app.api import zones as zones_mod
from app.builder import pipeline as pipeline_mod
from app.runtime import registry_store as registry_mod


@pytest.fixture(autouse=True)
def temp_registry(tmp_path, monkeypatch):
    """Point registry at a temp dir + reset in-memory cache per test."""
    config_dir = str(tmp_path / "config")
    data_dir = str(tmp_path / "data")
    os.makedirs(config_dir, exist_ok=True)
    os.makedirs(f"{data_dir}/base", exist_ok=True)
    os.makedirs(f"{data_dir}/zones", exist_ok=True)
    monkeypatch.setattr(registry_mod, "config", type("MockConfig", (), {
        "registry_file": f"{config_dir}/registry.json",
        "data_dir": data_dir,
        "zones_dir": f"{data_dir}/zones",
        "osrm_port_start": 5000,
        "vroom_port_start": 3000,
        "max_active_zones": 20,
        "zone_ttl_days": 90,
        "evictor_interval_minutes": 10,
        "osrm_default_radius": 50,
        "base_pbf": f"{data_dir}/base/italy.osm.pbf",
        "geofabrik_url": "https://example.com/test.pbf",
        "car_lua": "/opt/car.lua",
        "vroom_express_dir": "/vroom-express",
        "osrm_mmap": True,
        "config_dir": config_dir,
    })())
    monkeypatch.setattr(registry_mod, "_cache", None)
    # Also patch app.config.config used by other modules
    from app import config as config_mod
    monkeypatch.setattr(config_mod, "config", registry_mod.config)
    yield


def _sample_polygon():
    """Return a minimal valid GeoJSON polygon Feature."""
    return {
        "type": "Feature",
        "geometry": {
            "type": "Polygon",
            "coordinates": [[[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 1.0], [0.0, 0.0]]],
        },
    }


def _zone_id_from_polygon(polygon):
    """Mirror create_zone's deterministic zone_id derivation."""
    polygon_bytes = json.dumps(polygon, separators=(",", ":"), sort_keys=True).encode()
    return hashlib.sha256(polygon_bytes).hexdigest()[:12]


async def _never_run(*args, **kwargs):
    """Slow build coroutine that waits forever."""
    await asyncio.Event().wait()


def test_create_zone_registers_building_before_returning():
    """create_zone must register the zone in registry before returning."""
    polygon = _sample_polygon()

    with patch("app.api.zones.ensure_base_pbf", new_callable=AsyncMock), \
         patch("app.api.zones.build_zone", side_effect=_never_run), \
         patch("os.path.isfile", return_value=True), \
         patch("os.path.getmtime", return_value=12345.0):
        result = asyncio.run(zones_mod.create_zone(polygon))

    zone_id = result["zone_id"]
    record = asyncio.run(registry_mod.get_zone(zone_id))
    assert record is not None
    assert record.get("status") == "building"
    assert "polygon_geojson" in record
    assert record["polygon_geojson"] == polygon


def test_delete_cancels_in_flight_build():
    """Deleting a building zone must cancel/await the in-flight build task."""
    polygon = _sample_polygon()
    created_tasks = []

    async def slow_build(*args, **kwargs):
        await asyncio.Event().wait()

    real_create_task = asyncio.create_task

    def tracking_create_task(coro, **kwargs):
        task = real_create_task(coro, **kwargs)
        created_tasks.append(task)
        return task

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    try:
        with patch("app.api.zones.ensure_base_pbf", new_callable=AsyncMock), \
             patch("app.api.zones.list_zones", return_value=[]), \
             patch("app.api.zones.build_zone", side_effect=slow_build), \
             patch("app.api.zones.stop_zone", new_callable=AsyncMock), \
             patch("shutil.rmtree"), \
             patch("os.path.isdir", return_value=True), \
             patch("os.path.isfile", return_value=True), \
             patch("os.path.getmtime", return_value=12345.0), \
             patch("app.api.zones.asyncio.create_task", side_effect=tracking_create_task):
            result = loop.run_until_complete(zones_mod.create_zone(polygon))
            zone_id = result["zone_id"]
            loop.run_until_complete(zones_mod.delete_zone_endpoint(zone_id))

        assert created_tasks, "create_zone should have spawned a build task"
        assert created_tasks[0].done(), "in-flight build task must be cancelled before delete returns"
    finally:
        pending = [t for t in asyncio.all_tasks(loop) if not t.done()]
        for t in pending:
            t.cancel()
        if pending:
            loop.run_until_complete(asyncio.gather(*pending, return_exceptions=True))
        loop.close()


def test_reuse_checks_process_health():
    """Active zone reuse must verify subprocesses are actually alive."""
    polygon = _sample_polygon()
    polygon_bytes = json.dumps(polygon, separators=(",", ":"), sort_keys=True).encode()
    base_mtime = 12345.0
    polygon_hash, linestrings_hash, _ = registry_mod.compute_hashes(polygon_bytes, None, base_mtime)
    zone_id = _zone_id_from_polygon(polygon)

    asyncio.run(registry_mod.register_zone(zone_id, 10001, 11001,
                                            polygon_hash, linestrings_hash, base_mtime,
                                            polygon_geojson=polygon))
    asyncio.run(registry_mod.set_zone_status(zone_id, "active"))

    with patch("app.api.zones.all_zone_ids", return_value=set()), \
         patch("app.api.zones.ensure_base_pbf", new_callable=AsyncMock), \
         patch("app.api.zones.build_zone", side_effect=_never_run), \
         patch("os.path.isfile", return_value=True), \
         patch("os.path.getmtime", return_value=base_mtime):
        result = asyncio.run(zones_mod.create_zone(polygon))

    assert "reusing" not in result.get("message", "").lower()
    assert result.get("status") != "active"


def test_register_failure_releases_ports():
    """build_zone must release reserved ports if register_zone fails."""
    zone_id = "zoneabc123"
    polygon = _sample_polygon()

    with patch("app.builder.pipeline._get_zone_state", new_callable=AsyncMock, return_value={}), \
         patch("app.builder.pipeline.reserve_port_pair", return_value=(11111, 22222)), \
         patch("app.builder.pipeline.register_zone", side_effect=RuntimeError("db down")), \
         patch("app.builder.pipeline.release_port", new_callable=AsyncMock) as mock_release, \
         patch("app.builder.pipeline._file_mtime", return_value=12345.0):
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        try:
            try:
                loop.run_until_complete(pipeline_mod.build_zone(zone_id, polygon, None))
            except RuntimeError:
                pass
        finally:
            loop.close()

    mock_release.assert_any_call("osrm", 11111)
    mock_release.assert_any_call("vroom", 22222)


def test_rebuild_releases_stale_ports():
    """Dead-process rebuild must release old port reservations before re-registering."""
    polygon = _sample_polygon()
    polygon_bytes = json.dumps(polygon, separators=(",", ":"), sort_keys=True).encode()
    base_mtime = 12345.0
    polygon_hash, linestrings_hash, _ = registry_mod.compute_hashes(polygon_bytes, None, base_mtime)
    zone_id = _zone_id_from_polygon(polygon)

    asyncio.run(registry_mod.register_zone(zone_id, 11111, 22222,
                                            polygon_hash, linestrings_hash, base_mtime,
                                            polygon_geojson=polygon))
    asyncio.run(registry_mod.set_zone_status(zone_id, "active"))

    async def tracked_release(kind, port):
        return await registry_mod.release_port(kind, port)

    with patch("app.api.zones.all_zone_ids", return_value=set()), \
         patch("app.api.zones.ensure_base_pbf", new_callable=AsyncMock), \
         patch("app.api.zones.build_zone", side_effect=_never_run), \
         patch("app.api.zones.release_port", new_callable=AsyncMock, side_effect=tracked_release) as mock_release, \
         patch("os.path.isfile", return_value=True), \
         patch("os.path.getmtime", return_value=12345.0):
        result = asyncio.run(zones_mod.create_zone(polygon))

    assert result.get("status") != "active"
    mock_release.assert_any_call("osrm", 11111)
    mock_release.assert_any_call("vroom", 22222)


def test_release_port_failure_does_not_mask_register_error():
    """If register_zone fails, a release_port failure must not hide the original error."""
    polygon = _sample_polygon()

    with patch("app.api.zones.ensure_base_pbf", new_callable=AsyncMock), \
         patch("app.api.zones.register_zone", side_effect=RuntimeError("register boom")), \
         patch("app.api.zones.release_port", side_effect=RuntimeError("release boom")), \
         patch("os.path.isfile", return_value=True), \
         patch("os.path.getmtime", return_value=12345.0):
        with pytest.raises(RuntimeError, match="register boom"):
            asyncio.run(zones_mod.create_zone(polygon))


def test_delete_cancels_in_flight_start():
    """Deleting a built/starting zone must cancel/await the in-flight start task."""
    zone_id = "zoneabc123"

    asyncio.run(registry_mod.register_zone(zone_id, 5001, 3001, "hash", "", 12345.0,
                                            polygon_geojson=_sample_polygon()))
    asyncio.run(registry_mod.set_zone_status(zone_id, "built"))

    async def slow_start(*args, **kwargs):
        await asyncio.Event().wait()

    async def run_body():
        with patch("app.api.zones.stop_zone", new_callable=AsyncMock) as mock_stop, \
             patch("app.api.zones.start_zone", side_effect=slow_start), \
             patch("shutil.rmtree"), \
             patch("os.path.isdir", return_value=True):
            start_task = asyncio.create_task(zones_mod._start_after_build(zone_id))
            zones_mod._start_tasks[zone_id] = start_task
            start_task.add_done_callback(
                lambda t, zid=zone_id: zones_mod._start_tasks.pop(zid, None)
            )
            await asyncio.sleep(0)
            await zones_mod.delete_zone_endpoint(zone_id)

        assert start_task.done(), "in-flight start task must be cancelled before delete returns"
        mock_stop.assert_awaited_once_with(zone_id)

    zones_mod._start_tasks.clear()
    try:
        asyncio.run(run_body())
    finally:
        zones_mod._start_tasks.clear()


def test_registry_stores_polygon_geojson():
    """register_zone must store polygon_geojson in the registry record."""
    polygon = _sample_polygon()
    asyncio.run(registry_mod.register_zone("testzone", 5001, 3001, "hash", "", 12345.0,
                                            polygon_geojson=polygon))
    record = asyncio.run(registry_mod.get_zone("testzone"))
    assert record is not None
    assert record["polygon_geojson"] == polygon
    assert record["linestrings_geojson"] == {}


def test_registry_stores_linestrings_geojson():
    """register_zone must store linestrings_geojson in the registry record."""
    polygon = _sample_polygon()
    linestrings = {"type": "FeatureCollection", "features": []}
    asyncio.run(registry_mod.register_zone("testzone2", 5002, 3002, "hash", "lhash", 12345.0,
                                            polygon_geojson=polygon,
                                            linestrings_geojson=linestrings))
    record = asyncio.run(registry_mod.get_zone("testzone2"))
    assert record is not None
    assert record["linestrings_geojson"] == linestrings


def test_registry_persists_across_cache_reset():
    """Registry data must survive cache reset (simulates container restart)."""
    polygon = _sample_polygon()
    asyncio.run(registry_mod.register_zone("persistzone", 5003, 3003, "hash", "", 12345.0,
                                            polygon_geojson=polygon))

    # Reset cache — simulates new process reading from disk
    registry_mod._cache = None

    record = asyncio.run(registry_mod.get_zone("persistzone"))
    assert record is not None
    assert record["polygon_geojson"] == polygon
