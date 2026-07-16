package it.bluecube.osrmzonemanager.zone;

/**
 * Data carrier for boot-recovery: carries the geojson payloads and hash
 * needed to rebuild a zone, without exposing the full entity.
 */
public record ZoneRecoveryDTO(
        String zoneId,
        String status,
        String polygonHash,
        String polygonGeojson,
        String lineStringsGeojson
) {
}
