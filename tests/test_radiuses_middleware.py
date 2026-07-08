"""Tests for radiuses injection logic (app.api.radiuses).

Pure Python — no Docker stack, no FastAPI, no httpx/redis.
Just string parsing + regex. Runs anywhere.
"""

import hashlib


# ── route detection ────────────────────────────────────────────────────────


def test_is_route_or_table():
    from app.api import radiuses as rmod
    # Matches
    assert rmod.is_route_or_table("/route/v1/driving/10,20;30,40")
    assert rmod.is_route_or_table("/table/v1/cycling/a,b;c,d;e,f")
    # No match
    assert not rmod.is_route_or_table("/nearest/v1/driving/10,20")
    assert not rmod.is_route_or_table("/health")
    assert not rmod.is_route_or_table("/table/v2/other")


def test_extract_route_info():
    from app.api import radiuses as rmod
    # Route path
    svc, coords = rmod.extract_route_info("/route/v1/driving/10,20;30,40")
    assert svc == "route"
    assert coords == "10,20;30,40"
    # Table path
    svc, coords = rmod.extract_route_info("/table/v1/walking/a;b;c")
    assert svc == "table"
    assert coords == "a;b;c"
    # Non-match
    svc, coords = rmod.extract_route_info("/health")
    assert svc is None
    assert coords is None


# ── coord counting ────────────────────────────────────────────────────────


def test_count_raw_semicolon():
    from app.api import radiuses as rmod
    assert rmod.count_coords("13.38,52.51;13.39,52.52;13.40,52.53") == 3
    assert rmod.count_coords("10,20;30,40") == 2


def test_count_raw_single():
    from app.api import radiuses as rmod
    assert rmod.count_coords("10.0,20.0") == 1


def test_count_empty():
    from app.api import radiuses as rmod
    assert rmod.count_coords("") == 0
    assert rmod.count_coords("polyline(abc)") == 0


# ── radiuses injection ────────────────────────────────────────────────────


def test_non_route_passthrough():
    from app.api import radiuses as rmod
    result = rmod.inject_radiuses_query("", "/nearest/v1/driving/10,20")
    assert result == ""


def test_non_route_with_query_passthrough():
    from app.api import radiuses as rmod
    result = rmod.inject_radiuses_query("ping=true", "/health")
    assert result == "ping=true"


def test_inject_default_radius_semicolon_coords():
    from app.api import radiuses as rmod
    result = rmod.inject_radiuses_query(
        "overview=false",
        "/route/v1/driving/10,20;30,40",
        default_radius=50,
    )
    assert "radiuses=50;50" in result


def test_inject_custom_radius():
    from app.api import radiuses as rmod
    result = rmod.inject_radiuses_query(
        "",
        "/table/v1/driving/1;2;3",
        default_radius=200,
    )
    assert "radiuses=200;200;200" in result


def test_inject_header_override():
    from app.api import radiuses as rmod
    result = rmod.inject_radiuses_query(
        "",
        "/route/v1/driving/1;2",
        default_radius=50,
        header_radius=200,
    )
    assert "radiuses=200;200" in result


def test_client_radiuses_preserved_matching_count():
    from app.api import radiuses as rmod
    # Client sends 2 radiuses for 2 coords → keep them
    result = rmod.inject_radiuses_query(
        "radiuses=30;30&t=true",
        "/route/v1/driving/10,20;30,40",
        default_radius=50,
    )
    # Should NOT be overridden to 50;50 — client values kept
    assert "radiuses=30;30" in result


def test_mismatched_radiuses_raises_valueerror():
    from app.api import radiuses as rmod
    # 2 coords but only 1 radius → mismatch
    try:
        rmod.inject_radiuses_query(
            "radiuses=50",
            "/route/v1/driving/10,20;30,40",
        )
        assert False, "should have raised ValueError"
    except ValueError as exc:
        assert "mismatch" in str(exc).lower() or "entries" in str(exc).lower()


def test_single_coord_injects_single_radius():
    from app.api import radiuses as rmod
    result = rmod.inject_radiuses_query(
        "",
        "/route/v1/driving/10,20",
        default_radius=75,
    )
    assert "radiuses=75" in result
    assert "radiuses=75;75" not in result


# ── polyline counting (with optional lib) ─────────────────────────────────


def test_count_coords_with_polyline_mock():
    """Test polyline counting using mock — no real library needed."""
    import unittest.mock
    from app.api import radiuses as rmod

    mock_lib = unittest.mock.MagicMock()
    mock_lib.decode.return_value = [(1.0, 2.0), (3.0, 4.0), (5.0, 6.0)]

    with unittest.mock.patch.dict("sys.modules", {"polyline": mock_lib}):
        # Reload the module to pick up the mock
        import importlib
        # Force re-import of polyline within the module function call
        result = rmod.count_coords_with_polyline("polyline(gfo`F_@wB)")
        assert result == 3
        mock_lib.decode.assert_called()


def test_count_coords_without_polyline_falls_through():
    """When polyline lib missing, polyline() path returns 0."""
    import sys
    from app.api import radiuses as rmod

    # Remove polyline from sys.modules
    saved = sys.modules.pop("polyline", None)
    try:
        result = rmod.count_coords("polyline(abc)")
        assert result == 0  # Falls through to raw count → 0
    finally:
        if saved:
            sys.modules["polyline"] = saved


# ── content hash tests ────────────────────────────────────────────────────


def test_sha256_deterministic():
    data = b"test polygon content 12345"
    ph1 = hashlib.sha256(data).hexdigest()
    ph2 = hashlib.sha256(data).hexdigest()
    assert ph1 == ph2
    assert len(ph1) == 64


def test_sha256_different_inputs():
    h1 = hashlib.sha256(b"a").hexdigest()
    h2 = hashlib.sha256(b"b").hexdigest()
    assert h1 != h2
