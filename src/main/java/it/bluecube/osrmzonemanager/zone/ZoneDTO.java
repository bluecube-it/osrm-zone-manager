package it.bluecube.osrmzonemanager.zone;

import lombok.Builder;

import java.time.Instant;

/**
 * API-facing zone representation. Extends the entity view with per-request fields
 * {@code process} and {@code message}.
 */
@Builder
public record ZoneDTO(
        String zoneId,
        ZoneStatus status,
        Integer osrmPort,
        Integer vroomPort,
        Long osrmPid,
        Long vroomPid,
        String polygonHash,
        String lineStringsHash,
        String basePbfMtime,
        Instant createdAt,
        Instant lastAccess,
        Instant lastBuildAt,
        String error,
        String process,
        String message
) {
    /**
     * Returns a copy with the {@code process} field set.
     */
    public ZoneDTO withProcess(String process) {
        return new ZoneDTO(
                zoneId, status, osrmPort, vroomPort, osrmPid, vroomPid,
                polygonHash, lineStringsHash, basePbfMtime,
                createdAt, lastAccess, lastBuildAt, error, process, message
        );
    }

    /**
     * Returns a copy with the {@code message} field set.
     */
    public ZoneDTO withMessage(String message) {
        return new ZoneDTO(
                zoneId, status, osrmPort, vroomPort, osrmPid, vroomPid,
                polygonHash, lineStringsHash, basePbfMtime,
                createdAt, lastAccess, lastBuildAt, error, process, message
        );
    }
}
