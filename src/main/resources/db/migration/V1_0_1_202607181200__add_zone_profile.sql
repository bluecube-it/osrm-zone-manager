ALTER TABLE zones
    ADD COLUMN IF NOT EXISTS profile VARCHAR (64) NOT NULL DEFAULT 'CAR';

COMMENT
ON COLUMN zones.profile IS 'OSRM routing profile this zone was built with, enum name (CAR, BUS)';
