"""Base PBF download utility.

Fase 11 — call `ensure_base_pbf()` before first build to trigger a
download from geofabrik if the local file is missing / too small.
"""

import os
import sys

import httpx

from app.config import config
from app.utils.logger import get_logger

log = get_logger(__name__)

# Configurable: default 1 byte (only rejects empty/incomplete downloads).
# Set MIN_PBF_SIZE env var to enforce a minimum (e.g. 500000000 for full Italy).
MIN_PBF_SIZE = int(os.getenv("MIN_PBF_SIZE", "1"))


async def ensure_base_pbf() -> str:
    """Return path to valid base PBF. Downloads from geofabrik if needed.

    Raises on unrecoverable download failure.
    """
    pbf = config.base_pbf
    os.makedirs(os.path.dirname(pbf), exist_ok=True)

    if os.path.isfile(pbf) and os.path.getsize(pbf) > MIN_PBF_SIZE:
        log.info("base PBF ok: %s (%.1f MB)", pbf, os.path.getsize(pbf) / 1e6)
        return pbf

    url = config.geofabrik_url
    log.info("downloading base PBF from %s → %s", url, pbf)

    try:
        async with httpx.AsyncClient(timeout=1200) as client:
            async with client.stream("GET", url) as resp:
                resp.raise_for_status()
                total = resp.headers.get("content-length")
                downloaded = 0
                with open(pbf, "wb") as f:
                    async for chunk in resp.aiter_bytes(chunk_size=1_048_576):
                        f.write(chunk)
                        downloaded += len(chunk)
                        if total:
                            total_mb = int(total) / 1e6
                            pct = (downloaded / int(total)) * 100
                            sys.stdout.write(f"\r  {downloaded / 1e6:.1f} MB / "
                                             f"{total_mb:.1f} MB ({pct:.0f}%)  ")
                        else:
                            sys.stdout.write(f"\r  {downloaded / 1e6:.1f} MB  ")
                        sys.stdout.flush()
        sys.stdout.write("\n")
    except httpx.HTTPError as exc:
        raise RuntimeError(f"base PBF download failed: {exc}") from exc

    size = os.path.getsize(pbf)
    if size < MIN_PBF_SIZE:
        os.remove(pbf)
        raise RuntimeError(
            f"base PBF incomplete: {pbf} is {size / 1e6:.1f} MB (expected > 500 MB)"
        )

    log.info("base PBF downloaded: %s (%.1f MB)", pbf, size / 1e6)
    return pbf
