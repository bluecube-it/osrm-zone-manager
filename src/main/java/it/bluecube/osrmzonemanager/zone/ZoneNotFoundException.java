package it.bluecube.osrmzonemanager.zone;

/**
 * Thrown when a requested zone does not exist in the registry.
 */
public class ZoneNotFoundException extends RuntimeException {
    private final String zoneId;

    public ZoneNotFoundException(String zoneId) {
        super("zone " + zoneId + " not found");
        this.zoneId = zoneId;
    }

    public String zoneId() {
        return zoneId;
    }
}
