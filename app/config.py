"""Runtime config from env (read once at import)."""

import os
from dataclasses import dataclass, field


@dataclass
class Config:
    data_dir: str = field(default_factory=lambda: os.getenv("DATA_DIR", "/data"))
    base_pbf: str = field(default_factory=lambda: os.getenv("BASE_PBF", "/data/base/italy.osm.pbf"))
    geofabrik_url: str = field(default_factory=lambda: os.getenv(
        "GEOFABRIK_URL", "https://download.geofabrik.de/europe/italy-latest.osm.pbf"
    ))
    car_lua: str = "/opt/car.lua"
    vroom_express_dir: str = "/vroom-express"

    # zone lifecycle
    zone_ttl_days: int = field(default_factory=lambda: int(os.getenv("ZONE_TTL_DAYS", "90")))
    max_active_zones: int = field(default_factory=lambda: int(os.getenv("MAX_ACTIVE_ZONES", "20")))

    # ports (per-zone osrm on 5xxx, vroom-express on 3xxx)
    osrm_port_start: int = 5000
    vroom_port_start: int = 3000

    # radiuses middleware (condition #4: decode polyline + count)
    osrm_default_radius: int = field(default_factory=lambda: int(os.getenv("OSRM_DEFAULT_RADIUS", "50")))

    # evictor runs every N minutes
    evictor_interval_minutes: int = field(default_factory=lambda: int(os.getenv("EVICTOR_INTERVAL_MIN", "10")))

    # builder: use mmap for osrm-routed to keep RSS low
    osrm_mmap: bool = True

    @property
    def zones_dir(self) -> str:
        return f"{self.data_dir}/zones"


config = Config()
