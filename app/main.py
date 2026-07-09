"""FastAPI orchestrator entrypoint.

Boot recovery + evictor worker are started in the `lifespan` context.
Endpoints and proxy logic are wired here, implemented in app.api.*.
"""

from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.config import config
from app.utils.logger import get_logger

log = get_logger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Run on boot: recover active zones, start evictor worker."""
    log.info("boot: osrm-zone-manager starting")
    log.info("boot: DATA_DIR=%s BASE_PBF=%s", config.data_dir, config.base_pbf)

    # ensure base PBF (download from geofabrik if missing) — fail fast
    from app.utils.pbf import ensure_base_pbf
    await ensure_base_pbf()

    # boot recovery (Fase 10) — imported lazily to avoid circulars
    from app.runtime.boot import recover_zones
    await recover_zones()

    # evictor worker (Fase 9) — background task
    from app.evictor.worker import start_evictor
    task = await start_evictor()

    yield

    log.info("shutdown: stopping evictor")
    task.cancel()
    # stop all zone subprocesses (Fase 5)
    from app.runtime.supervisor import stop_all_zones
    await stop_all_zones()
    log.info("shutdown: done")


app = FastAPI(
    title="osrm-zone-manager",
    description="Multi-zone OSRM + VROOM orchestrator",
    version="0.1.0",
    lifespan=lifespan,
)


@app.get("/health")
async def health():
    return {"status": "ok"}


# Wire routers (Fase 6, 7)
from app.api.zones import router as zones_router  # noqa: E402
from app.api.proxy import router as proxy_router  # noqa: E402

app.include_router(zones_router)
app.include_router(proxy_router)
