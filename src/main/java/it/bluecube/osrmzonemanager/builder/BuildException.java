package it.bluecube.osrmzonemanager.builder;

/**
 * Thrown when a subprocess managed by the build pipeline fails.
 * e.g., due to non-zero exit code or timeout.
 */
public class BuildException extends RuntimeException {
    public BuildException(String message) {
        super(message);
    }

    public BuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
