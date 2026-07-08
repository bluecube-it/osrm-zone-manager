"""Pure-Python radiuses middleware logic.

No external deps except the `polyline` library (optional — if missing,
polyline-encoded coords fall back to not counting).

Used by app/api/proxy.py at runtime. Standalone — testable without
Docker stack or any other project deps.
"""

import re
from urllib.parse import parse_qs


class PolylineDecodeError(ValueError):
    """Raised when polyline decode fails in count_coords_with_polyline."""


# ── route detection ───────────────────────────────────────────────────────

_ROUTE_OR_TABLE = re.compile(r"^/(route|table)/v1/[^/]+/(.+)$")


def is_route_or_table(path: str) -> bool:
    """True if path needs radiuses injection."""
    return bool(_ROUTE_OR_TABLE.match(path))


def extract_route_info(path: str) -> tuple:
    """Return (service_type, coords_string) or (None, None)."""
    m = _ROUTE_OR_TABLE.match(path)
    if not m:
        return None, None
    return m.group(1), m.group(2)


# ── coord counting ────────────────────────────────────────────────────────


def count_coords(coord_part: str) -> int:
    """Count coordinates in a path coords segment.

    Raw:  13.38,52.51;13.39,52.52 → 2
    Single: 10.0,20.0 → 1
    Empty → 0
    Not recognized → 0 (caller falls back to pass-through)
    """
    if ";" in coord_part:
        return len(coord_part.split(";"))
    if "," in coord_part:
        return 1
    return 0


def count_coords_with_polyline(coord_part: str) -> int:
    """Like count_coords but also decodes polyline(...)/polyline6(...).

    Returns the coord count for raw coords or decoded polyline length.
    Raises ValueError if polyline decode fails.
    """
    if coord_part.startswith("polyline6("):
        prefix = "polyline6("
        return _decode_polyline(coord_part, prefix, precision=6)
    if coord_part.startswith("polyline("):
        prefix = "polyline("
        return _decode_polyline(coord_part, prefix, precision=5)
    return count_coords(coord_part)


def _decode_polyline(coord_part: str, prefix: str, precision: int) -> int:
    """Decode polyline-encoded coords, return point count.

    Raises ValueError on decode failure or missing lib.
    """
    encoded = coord_part[len(prefix):]
    if not encoded or encoded.endswith(")"):
        encoded = encoded.rstrip(")")
    try:
        import polyline
    except ImportError:
        raise PolylineDecodeError(
            f"polyline lib not installed — cannot decode "
            f"{prefix}encoded string ({encoded[:40]}...)"
        )
    try:
        points = polyline.decode(encoded, precision=precision)
    except Exception as exc:
        raise PolylineDecodeError(
            f"polyline decode failed: {exc} (input: {encoded[:40]}...)"
        ) from exc
    return len(points)


# ── radiuses injection ────────────────────────────────────────────────────


def inject_radiuses_query(
    original_query: str,
    path: str,
    default_radius: int = 50,
    header_radius: int | None = None,
) -> str:
    """Inject/modify radiuses in query string for route/table requests.

    Args:
        original_query:  raw query string from URL
        path:            URL path (/route/v1/driving/1,2;3,4)
        default_radius:  default radius value (meters)
        header_radius:   X-OSRM-Radius override (None → use default)

    Returns:
        Modified query string with radiuses set.

    Raises:
        ValueError: if client `radiuses` param exists but count mismatches path coords.
    """
    if not is_route_or_table(path):
        return original_query

    _, coord_part = extract_route_info(path)
    if coord_part is None:
        return original_query

    coord_count = count_coords_with_polyline(coord_part)
    if coord_count <= 0:
        # Not a valid coord format — pass through
        return original_query

    # Determine effective radius per point
    if header_radius is not None:
        radius = header_radius
    else:
        radius = default_radius

    # Check for client-provided radiuses
    existing_qs = parse_qs(original_query, keep_blank_values=True)
    client_radiuses = existing_qs.get("radiuses", [None])[0]

    if client_radiuses is not None:
        client_count = len(client_radiuses.split(";"))
        if client_count != coord_count:
            raise ValueError(
                f"client radiuses has {client_count} entries "
                f"but path has {coord_count} coordinates"
            )
        radiuses_str = client_radiuses
    else:
        radiuses_str = ";".join([str(radius)] * coord_count)

    # Append/replace radiuses in query
    if original_query:
        # Remove existing radiuses param if present
        segments = original_query.split("&")
        segments = [s for s in segments if not s.startswith("radiuses=")]
        return "&".join(segments + [f"radiuses={radiuses_str}"]) if segments else f"radiuses={radiuses_str}"
    else:
        return f"radiuses={radiuses_str}"
