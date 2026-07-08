"""RED-phase contract tests for zone lifecycle race conditions.

Pure Python — no Docker, no real Redis, no real subprocesses.
These tests exercise the invariants that the fixes must guarantee.
"""

import asyncio
import hashlib
import json
from unittest.mock import patch, AsyncMock, MagicMock

import pytest

from app.api import zones as zones_mod
from app.builder import pipeline as pipeline_mod
from app.runtime import redis_client as redis_mod


class FakeRedis:
    """Minimal in-memory async Redis for unit tests."""

    def __init__(self):
        self.hashes = {}
        self.sets = {}

    async def hgetall(self, key):
        """Return a copy of the hash."""
        return dict(self.hashes.get(key, {}))

    async def hset(self, key, field=None, value=None, mapping=None):
        """Support both single-field and bulk mapping writes."""
        h = self.hashes.setdefault(key, {})
        if mapping is not None:
            h.update(mapping)
        elif field is not None:
            h[field] = value
        return 1

    async def smembers(self, key):
        """Return set members as a list."""
        return list(self.sets.get(key, set()))

    async def sadd(self, key, *values):
        """Add values to a set."""
        s = self.sets.setdefault(key, set())
        added = 0
        for v in values:
            if v not in s:
                s.add(v)
                added += 1
        return added

    async def srem(self, key, *values):
        """Remove values from a set."""
        s = self.sets.get(key, set())
        removed = 0
        for v in values:
            if v in s:
                s.remove(v)
                removed += 1
        return removed

    async def scard(self, key):
        """Return set cardinality."""
        return len(self.sets.get(key, set()))

    async def delete(self, key):
        """Delete hash and/or set keys."""
        count = 0
        if key in self.hashes:
            del self.hashes[key]
            count += 1
        if key in self.sets:
            del self.sets[key]
            count += 1
        return count

    def pipeline(self):
        """Return a synchronous pipeline builder."""
        return FakePipeline(self)


class FakePipeline:
    """Pipeline command accumulator for FakeRedis."""

    def __init__(self, redis):
        self._redis = redis
        self._cmds = []

    def hset(self, key, mapping=None, field=None, value=None):
        """Queue an hset command."""
        self._cmds.append(("hset", key, mapping, field, value))
        return self

    def sadd(self, key, *values):
        """Queue an sadd command."""
        self._cmds.append(("sadd", key, values))
        return self

    def srem(self, key, *values):
        """Queue an srem command."""
        self._cmds.append(("srem", key, values))
        return self

    def delete(self, key):
        """Queue a delete command."""
        self._cmds.append(("delete", key))
        return self

    async def execute(self):
        """Replay queued commands and return their results."""
        results = []
        for cmd in self._cmds:
            name = cmd[0]
            if name == "hset":
                _, key, mapping, field, value = cmd
                results.append(await self._redis.hset(key, field=field, value=value, mapping=mapping))
            elif name == "sadd":
                _, key, values = cmd
                results.append(await self._redis.sadd(key, *values))
            elif name == "srem":
                _, key, values = cmd
                results.append(await self._redis.srem(key, *values))
            elif name == "delete":
                _, key = cmd
                results.append(await self._redis.delete(key))
        self._cmds = []
        return results


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
    """create_zone must register the zone in Redis before returning."""
    polygon = _sample_polygon()
    fake = FakeRedis()

    with patch("app.api.zones.get_redis", return_value=fake), \
         patch("app.runtime.redis_client.get_redis", return_value=fake), \
         patch("app.api.zones.ensure_base_pbf", new_callable=AsyncMock), \
         patch("app.api.zones.build_zone", side_effect=_never_run), \
         patch("os.path.isfile", return_value=True), \
         patch("os.path.getmtime", return_value=12345.0):
        result = asyncio.run(zones_mod.create_zone(polygon))

    zone_id = result["zone_id"]
    record = fake.hashes.get(f"osrm:zones:{zone_id}", {})
    assert record.get("status") == "building"


def test_delete_cancels_in_flight_build():
    """Deleting a building zone must cancel/await the in-flight build task."""
    polygon = _sample_polygon()
    fake = FakeRedis()
    created_tasks = []

    async def slow_build(*args, **kwargs):
        """Build coroutine that never completes until cancelled."""
        await asyncio.Event().wait()

    real_create_task = asyncio.create_task

    def tracking_create_task(coro, **kwargs):
        """Wrap asyncio.create_task and record created tasks."""
        task = real_create_task(coro, **kwargs)
        created_tasks.append(task)
        return task

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    try:
        with patch("app.api.zones.get_redis", return_value=fake), \
             patch("app.runtime.redis_client.get_redis", return_value=fake), \
             patch("app.api.zones.ensure_base_pbf", new_callable=AsyncMock), \
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

            # Simulate the record that the fixed create_zone would have written.
            loop.run_until_complete(fake.hset(f"osrm:zones:{zone_id}", mapping={
                "zone_id": zone_id,
                "status": "building",
                "osrm_port": "5001",
                "vroom_port": "3001",
            }))
            loop.run_until_complete(fake.sadd("osrm:zones", zone_id))

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
    polygon_hash, linestrings_hash, _ = redis_mod.compute_hashes(polygon_bytes, None, base_mtime)
    zone_id = _zone_id_from_polygon(polygon)

    fake = FakeRedis()
    asyncio.run(fake.hset(f"osrm:zones:{zone_id}", mapping={
        "zone_id": zone_id,
        "polygon_hash": polygon_hash,
        "linestrings_hash": linestrings_hash,
        "base_pbf_mtime": str(base_mtime),
        "status": "active",
        "osrm_port": "10001",
        "vroom_port": "11001",
    }))
    asyncio.run(fake.sadd("osrm:zones", zone_id))

    with patch("app.api.zones.get_redis", return_value=fake), \
         patch("app.runtime.redis_client.get_redis", return_value=fake), \
         patch("app.api.zones.all_zone_ids", return_value=set()), \
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
    fake = FakeRedis()

    with patch("app.builder.pipeline.get_redis", return_value=fake), \
         patch("app.builder.pipeline._get_zone_state", new_callable=AsyncMock, return_value={}), \
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
    polygon_hash, linestrings_hash, _ = redis_mod.compute_hashes(polygon_bytes, None, base_mtime)
    zone_id = _zone_id_from_polygon(polygon)
    fake = FakeRedis()

    asyncio.run(fake.hset(f"osrm:zones:{zone_id}", mapping={
        "zone_id": zone_id,
        "polygon_hash": polygon_hash,
        "linestrings_hash": linestrings_hash,
        "base_pbf_mtime": str(base_mtime),
        "status": "active",
        "osrm_port": "11111",
        "vroom_port": "22222",
    }))
    asyncio.run(fake.sadd("osrm:zones", zone_id))
    asyncio.run(fake.sadd("osrm:ports:osrm", "11111"))
    asyncio.run(fake.sadd("osrm:ports:vroom", "22222"))

    async def tracked_release(kind, port):
        """Call real release_port so the FakeRedis set is updated, while counting calls."""
        return await redis_mod.release_port(kind, port)

    with patch("app.api.zones.get_redis", return_value=fake), \
         patch("app.runtime.redis_client.get_redis", return_value=fake), \
         patch("app.api.zones.all_zone_ids", return_value=set()), \
         patch("app.api.zones.ensure_base_pbf", new_callable=AsyncMock), \
         patch("app.api.zones.build_zone", side_effect=_never_run), \
         patch("app.api.zones.release_port", new_callable=AsyncMock, side_effect=tracked_release) as mock_release, \
         patch("os.path.isfile", return_value=True), \
         patch("os.path.getmtime", return_value=12345.0):
        result = asyncio.run(zones_mod.create_zone(polygon))

    assert result.get("status") != "active"
    mock_release.assert_any_call("osrm", 11111)
    mock_release.assert_any_call("vroom", 22222)
    assert "11111" not in fake.sets.get("osrm:ports:osrm", set())
    assert "22222" not in fake.sets.get("osrm:ports:vroom", set())


def test_release_port_failure_does_not_mask_register_error():
    """If register_zone fails, a release_port failure must not hide the original error."""
    polygon = _sample_polygon()
    fake = FakeRedis()

    with patch("app.api.zones.get_redis", return_value=fake), \
         patch("app.runtime.redis_client.get_redis", return_value=fake), \
         patch("app.api.zones.ensure_base_pbf", new_callable=AsyncMock), \
         patch("app.api.zones.register_zone", side_effect=RuntimeError("register boom")), \
         patch("app.api.zones.release_port", side_effect=RuntimeError("release boom")), \
         patch("os.path.isfile", return_value=True), \
         patch("os.path.getmtime", return_value=12345.0):
        with pytest.raises(RuntimeError, match="register boom"):
            asyncio.run(zones_mod.create_zone(polygon))


def test_delete_cancels_in_flight_start():
    """Deleting a built/starting zone must cancel/await the in-flight start task."""
    zone_id = "zoneabc123"
    fake = FakeRedis()

    asyncio.run(fake.hset(f"osrm:zones:{zone_id}", mapping={
        "zone_id": zone_id,
        "status": "built",
        "osrm_port": "5001",
        "vroom_port": "3001",
    }))
    asyncio.run(fake.sadd("osrm:zones", zone_id))

    async def slow_start(*args, **kwargs):
        """start_zone coroutine that never completes until cancelled."""
        await asyncio.Event().wait()

    async def run_body():
        with patch("app.api.zones.get_redis", return_value=fake), \
             patch("app.runtime.redis_client.get_redis", return_value=fake), \
             patch("app.api.zones.stop_zone", new_callable=AsyncMock) as mock_stop, \
             patch("app.api.zones.start_zone", side_effect=slow_start), \
             patch("shutil.rmtree"), \
             patch("os.path.isdir", return_value=True):
            start_task = asyncio.create_task(zones_mod._start_after_build(zone_id))
            zones_mod._start_tasks[zone_id] = start_task
            start_task.add_done_callback(
                lambda t, zid=zone_id: zones_mod._start_tasks.pop(zid, None)
            )
            # Let the start task set status "starting" and block inside start_zone.
            await asyncio.sleep(0)
            await zones_mod.delete_zone_endpoint(zone_id)

        assert start_task.done(), "in-flight start task must be cancelled before delete returns"
        mock_stop.assert_awaited_once_with(zone_id)

    zones_mod._start_tasks.clear()
    try:
        asyncio.run(run_body())
    finally:
        zones_mod._start_tasks.clear()
