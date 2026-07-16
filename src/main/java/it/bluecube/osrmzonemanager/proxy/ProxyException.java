package it.bluecube.osrmzonemanager.proxy;

/**
 * Generic proxy error (IOException, unexpected error during forwarding).
 * Controller translates to HTTP 502.
 */
public class ProxyException extends RuntimeException {
    public ProxyException(String message) {
        super(message);
    }

    public ProxyException(String message, Throwable cause) {
        super(message, cause);
    }
}
