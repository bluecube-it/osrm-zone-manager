package it.bluecube.osrmzonemanager.maps;

/**
 * Thrown when the base PBF file is not found at the configured path.
 * Mapped to HTTP 503 Service Unavailable.
 */
public class MissingBasePbfException extends RuntimeException {
    private final String pbfPath;

    public MissingBasePbfException(String pbfPath) {
        super("Base PBF not found or too small at: " + pbfPath);
        this.pbfPath = pbfPath;
    }

    public String pbfPath() {
        return pbfPath;
    }
}
