package it.bluecube.osrmzonemanager.global;

/**
 * Thrown when a request targets a global profile whose whole-map OSRM instance is not (yet)
 * serving — building, starting, failed, or unknown. Translated to HTTP 503.
 */
public class GlobalOsrmUnavailableException extends RuntimeException {

    /**
     * @param profile routing profile name as requested by the client
     * @param status  current lifecycle status
     * @param error   optional failure reason
     */
    public GlobalOsrmUnavailableException(String profile, String status, String error) {
        super("global osrm profile '" + profile + "' is not available (status=" + status + ")"
                + (error == null || error.isBlank() ? "" : ": " + error));
    }
}
