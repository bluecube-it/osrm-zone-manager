package it.bluecube.osrmzonemanager.proxy;

/**
 * Thrown when the OSRM/VROOM target is unreachable (connection refused).
 * Controller translates to HTTP 502.
 */
public class ProxyTargetUnreachableException extends RuntimeException {
    public ProxyTargetUnreachableException(String message) {
        super(message);
    }

    public ProxyTargetUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}
