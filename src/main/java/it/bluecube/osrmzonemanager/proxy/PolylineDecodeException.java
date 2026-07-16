package it.bluecube.osrmzonemanager.proxy;

public class PolylineDecodeException extends IllegalArgumentException {
    public PolylineDecodeException(String message) {
        super(message);
    }

    public PolylineDecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}