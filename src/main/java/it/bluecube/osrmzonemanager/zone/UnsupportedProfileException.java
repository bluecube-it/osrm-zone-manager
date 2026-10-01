package it.bluecube.osrmzonemanager.zone;

import java.util.Collection;

/**
 * Thrown when a zone is requested with a routing profile the manager does not ship.
 * Translated to HTTP 400 by the global exception handler.
 */
public class UnsupportedProfileException extends RuntimeException {

    /**
     * @param profile   the rejected profile name (normalized)
     * @param supported the profile names accepted by the manager
     */
    public UnsupportedProfileException(String profile, Collection<String> supported) {
        super("unsupported profile '" + profile + "' — supported: " + String.join(", ", supported));
    }
}
