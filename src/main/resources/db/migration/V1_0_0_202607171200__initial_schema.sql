CREATE TABLE IF NOT EXISTS zones
(
    zone_id              VARCHAR(255) PRIMARY KEY,
    version              BIGINT       NOT NULL DEFAULT 0,
    polygon_hash         VARCHAR(255),
    line_strings_hash    VARCHAR(255) NOT NULL DEFAULT '',
    base_pbf_mtime       VARCHAR(255),
    status               VARCHAR(255),
    osrm_port            INTEGER      NOT NULL DEFAULT 0,
    vroom_port           INTEGER      NOT NULL DEFAULT 0,
    osrm_pid             BIGINT       NOT NULL DEFAULT 0,
    vroom_pid            BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMP WITH TIME ZONE,
    last_access          TIMESTAMP WITH TIME ZONE,
    last_build_at        TIMESTAMP WITH TIME ZONE,
    error                TEXT         NOT NULL DEFAULT '',
    polygon_geojson      TEXT,
    line_strings_geojson TEXT
);

CREATE INDEX zones_last_access_idx ON zones (last_access DESC);
