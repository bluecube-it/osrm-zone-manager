"""Tests for proxy-level radiuses: inject_radiuses_query with polyline,
and PolylineDecodeError handling.

Pure Python — no Docker stack, no FastAPI, no httpx/redis.
"""


# ── polyline injection with mocked library ─────────────────────────────────


def test_inject_radiuses_polyline_coords():
    """Inject works with polyline-encoded coordinates (mocked decode)."""
    import unittest.mock
    from app.api import radiuses as rmod

    mock_lib = unittest.mock.MagicMock()
    mock_lib.decode.return_value = [(1.0, 2.0), (3.0, 4.0), (5.0, 6.0)]

    with unittest.mock.patch.dict("sys.modules", {"polyline": mock_lib}):
        result = rmod.inject_radiuses_query(
            "overview=false",
            "/route/v1/driving/polyline(gfo`F_@wB)",
            default_radius=50,
        )
        assert "radiuses=50;50;50" in result


def test_inject_radiuses_polyline6_coords():
    """Inject works with polyline6-encoded coordinates (mocked decode)."""
    import unittest.mock
    from app.api import radiuses as rmod

    mock_lib = unittest.mock.MagicMock()
    mock_lib.decode.return_value = [(10.0, 20.0), (30.0, 40.0)]

    with unittest.mock.patch.dict("sys.modules", {"polyline": mock_lib}):
        result = rmod.inject_radiuses_query(
            "",
            "/route/v1/driving/polyline6(xyz123)",
            default_radius=200,
        )
        assert "radiuses=200;200" in result


def test_inject_radiuses_client_polyline_mismatch():
    """Client sends wrong radius count for polyline coords → ValueError."""
    import unittest.mock
    from app.api import radiuses as rmod

    mock_lib = unittest.mock.MagicMock()
    mock_lib.decode.return_value = [(1.0, 2.0), (3.0, 4.0), (5.0, 6.0)]

    with unittest.mock.patch.dict("sys.modules", {"polyline": mock_lib}):
        try:
            rmod.inject_radiuses_query(
                "radiuses=50;50",  # 2 entries but 3 polyline points
                "/route/v1/driving/polyline(gfo`F_@wB)",
            )
            assert False, "should have raised ValueError"
        except ValueError as exc:
            assert "3" in str(exc) or "entries" in str(exc).lower()


def test_inject_radiuses_polyline_client_preserved():
    """Client sends matching radius count for polyline → kept."""
    import unittest.mock
    from app.api import radiuses as rmod

    mock_lib = unittest.mock.MagicMock()
    mock_lib.decode.return_value = [(1.0, 2.0), (3.0, 4.0), (5.0, 6.0)]

    with unittest.mock.patch.dict("sys.modules", {"polyline": mock_lib}):
        result = rmod.inject_radiuses_query(
            "radiuses=10;20;30&t=1",
            "/route/v1/driving/polyline(gfo`F_@wB)",
            default_radius=50,
        )
        assert "radiuses=10;20;30" in result


# ── PolylineDecodeError ────────────────────────────────────────────────────


def test_decode_error_on_bad_polyline():
    """Bad polyline content → PolylineDecodeError."""
    import unittest.mock
    from app.api import radiuses as rmod

    mock_lib = unittest.mock.MagicMock()
    mock_lib.decode.side_effect = Exception("invalid encoding")

    with unittest.mock.patch.dict("sys.modules", {"polyline": mock_lib}):
        try:
            rmod.count_coords_with_polyline("polyline(!!!invalid!!!)")
            assert False, "should have raised PolylineDecodeError"
        except rmod.PolylineDecodeError as exc:
            assert "decode failed" in str(exc).lower()


def test_decode_error_propagates_inject_radiuses():
    """Bad polyline in inject_radiuses_query → PolylineDecodeError."""
    import unittest.mock
    from app.api import radiuses as rmod

    mock_lib = unittest.mock.MagicMock()
    mock_lib.decode.side_effect = Exception("malformed")

    with unittest.mock.patch.dict("sys.modules", {"polyline": mock_lib}):
        try:
            rmod.inject_radiuses_query(
                "",
                "/route/v1/driving/polyline(bad!@#)",
            )
            assert False, "should have raised PolylineDecodeError"
        except rmod.PolylineDecodeError as exc:
            assert "decode" in str(exc).lower()


def test_polyline_decode_error_subclass_of_valueerror():
    """PolylineDecodeError extends ValueError so existing callers catch it."""
    from app.api import radiuses as rmod

    try:
        raise rmod.PolylineDecodeError("test")
    except ValueError:
        pass  # Should be caught
    else:
        assert False, "PolylineDecodeError should be a ValueError subclass"


def test_count_coords_with_polyline_raw_falls_through():
    """Raw semicolon coords still work when polyline lib missing."""
    import sys
    from app.api import radiuses as rmod

    saved = sys.modules.pop("polyline", None)
    try:
        result = rmod.count_coords_with_polyline("10,20;30,40;50,60")
        assert result == 3
    finally:
        if saved:
            sys.modules["polyline"] = saved
