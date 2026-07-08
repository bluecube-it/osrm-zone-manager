"""Proxy router: /{zone_id}/osrm/* and /{zone_id}/vroom/*.

Fase 6+7 — forwards to per-zone osrm-routed/vroom-express. Includes
radiuses injection via app.api.radiuses (pure-Python, testable standalone).
"""

from typing import Optional
import json

import httpx
from fastapi import APIRouter, Request, HTTPException
from fastapi.responses import StreamingResponse

from app.config import config
from app.runtime.redis_client import get_zone, touch_zone
from app.api import radiuses as rmod
from app.utils.logger import get_logger


def _make_error_response(code: str, message: str) -> StreamingResponse:
    """Return an HTTP 400 StreamingResponse with JSON error body."""
    return StreamingResponse(
        iter([json.dumps({"code": code, "message": message}).encode()]),
        status_code=400,
        media_type="application/json",
    )

log = get_logger(__name__)

router = APIRouter(tags=["proxy"])

_http_client: Optional[httpx.AsyncClient] = None


def _get_client() -> httpx.AsyncClient:
    global _http_client
    if _http_client is None:
        _http_client = httpx.AsyncClient(timeout=120)
    return _http_client


# ── proxy handlers ─────────────────────────────────────────────────────────


@router.api_route("/{zone_id}/osrm/{path:path}", methods=["GET", "POST"])
async def proxy_osrm(zone_id: str, request: Request, path: str):
    """Proxy to 127.0.0.1:<zone_osrm_port>/<path> with radiuses injection."""
    resp = await _proxy(zone_id, "osrm", path, request)
    return _stream_response(resp)


@router.api_route("/{zone_id}/vroom/{path:path}", methods=["GET", "POST"])
async def proxy_vroom(zone_id: str, request: Request, path: str):
    """Proxy to 127.0.0.1:<zone_vroom_port>/<path>."""
    resp = await _proxy(zone_id, "vroom", path, request)
    return _stream_response(resp)


async def _proxy(
    zone_id: str, service: str, path: str, request: Request
) -> StreamingResponse:
    """Core: lookup zone, inject radiuses, forward via httpx."""
    zone = await get_zone(zone_id)
    if not zone:
        raise HTTPException(status_code=404, detail=f"zone {zone_id} not found")

    status = zone.get("status", "")
    if status not in ("active", "degraded"):
        raise HTTPException(
            status_code=503,
            detail=f"zone {zone_id} is {status or 'unknown'}"
                   + (f": {zone.get('error', '')}" if zone.get("error") else "")
                   + " — not yet available",
        )

    port = int(zone.get(f"{service}_port", 0))
    body = await request.body() if request.method == "POST" else None

    # Update last_access
    await touch_zone(zone_id)

    # ── radiuses injection (osrm only) ─────────────────────────────
    new_query = request.url.query
    if service == "osrm":
        radius = config.osrm_default_radius
        hdr = request.headers.get("x-osrm-radius")
        try:
            hdr_radius = int(hdr) if hdr else None
        except (ValueError, TypeError):
            hdr_radius = None
        try:
            new_query = rmod.inject_radiuses_query(
                request.url.query, path,
                default_radius=radius,
                header_radius=hdr_radius,
            )
        except rmod.PolylineDecodeError as exc:
            return _make_error_response("InvalidUrl", f"polyline decode failed: {exc}")
        except ValueError as exc:
            return _make_error_response("RadiusMismatch", str(exc))

    # Rebuild target
    full_target = f"http://127.0.0.1:{port}/{path}"
    if new_query:
        full_target += "?" + new_query

    # Forward
    headers = {k: v for k, v in request.headers.items()
               if k.lower() not in ("host", "transfer-encoding", "accept-encoding")}
    headers["host"] = f"127.0.0.1:{port}"
    headers["accept-encoding"] = "identity"

    client = _get_client()
    try:
        if request.method == "GET":
            resp = await client.get(full_target, headers=headers)
        elif request.method == "POST":
            resp = await client.post(
                full_target, headers=headers, content=body
            )
        else:
            resp = await client.request(
                request.method, full_target, headers=headers, content=body
            )
        return _stream_response(resp)
    except httpx.ConnectError as exc:
        raise HTTPException(
            status_code=502,
            detail=f"{service} unreachable at 127.0.0.1:{port}: {exc}",
        )


def _stream_response(resp) -> StreamingResponse:
    """Wrap httpx.Response → StreamingResponse.

    If resp is already a StreamingResponse (e.g. from _make_error_response),
    return it as-is to avoid double-wrap.
    """
    if isinstance(resp, StreamingResponse):
        return resp
    out_headers = {k: v for k, v in resp.headers.items()
                   if k.lower() not in ("content-encoding", "content-length", "transfer-encoding")}
    return StreamingResponse(
        iter([resp.read()]),
        status_code=resp.status_code,
        headers=out_headers,
        media_type=resp.headers.get("content-type", "application/octet-stream"),
    )
