package it.bluecube.osrmzonemanager.zone;

/**
 * Thrown when a zone with matching content is found but still building/starting —
 * the client should retry later (HTTP 409).
 */
public class ZoneInProgressException extends RuntimeException {
    private final String zoneId;
    private final String status;

    public ZoneInProgressException(String zoneId, String status) {
        super("zone " + zoneId + " with same content is " + status);
        this.zoneId = zoneId;
        this.status = status;
    }

    public String zoneId() {
        return zoneId;
    }

    public String status() {
        return status;
    }
}