package it.bluecube.osrmzonemanager.zone;

import java.time.Instant;

/**
 * Lightweight record for eviction candidate identification.
 */
public record ZoneEvictionCandidate(
        String zoneId,
        Instant lastAccess
) {
}
