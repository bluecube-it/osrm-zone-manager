package it.bluecube.osrmzonemanager.zone;

/**
 * Thrown when a zone is not active or degraded — proxy cannot forward.
 */
public class ZoneUnavailableException extends RuntimeException {
    private final String zoneId;
    private final String status;
    private final String error;

    public ZoneUnavailableException(String zoneId, String status, String error) {
        super(buildMessage(zoneId, status, error));
        this.zoneId = zoneId;
        this.status = status;
        this.error = error;
    }

    private static String buildMessage(String zoneId, String status, String error) {
        String base = "zone " + zoneId + " is " + status;
        if (error != null && !error.isEmpty()) {
            return base + ": " + error + " — not yet available";
        }
        return base + " — not yet available";
    }

    public String zoneId() {
        return zoneId;
    }

    public String status() {
        return status;
    }

    public String error() {
        return error;
    }
}
