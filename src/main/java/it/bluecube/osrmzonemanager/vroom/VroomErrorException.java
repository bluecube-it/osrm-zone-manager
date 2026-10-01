package it.bluecube.osrmzonemanager.vroom;

import org.springframework.http.HttpStatus;

/**
 * VROOM-shaped error raised before (or instead of) a {@code vroom} run.
 *
 * <p>The response body mirrors what the {@code vroom} binary itself emits: {@code {"code":N,"error":"..."}},
 * where {@code N} is the vroom exit-code vocabulary (1 internal, 2 input, 3 routing) plus {@code 4} used by
 * {@code vroom-express} for payload size checks.
 */
public class VroomErrorException extends RuntimeException {

    private final int code;
    private final HttpStatus status;

    private VroomErrorException(int code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    /**
     * Input error (vroom exit code 2) — translated to HTTP 400.
     *
     * @param message error message
     * @return the exception
     */
    public static VroomErrorException input(String message) {
        return new VroomErrorException(2, HttpStatus.BAD_REQUEST, message);
    }

    /**
     * Payload too large (code 4, size checks previously done by vroom-express) — translated to HTTP 413.
     *
     * @param message error message
     * @return the exception
     */
    public static VroomErrorException tooLarge(String message) {
        return new VroomErrorException(4, HttpStatus.PAYLOAD_TOO_LARGE, message);
    }

    /**
     * Internal error (vroom exit code 1) — translated to HTTP 500.
     *
     * @param message error message
     * @return the exception
     */
    public static VroomErrorException internal(String message) {
        return new VroomErrorException(1, HttpStatus.INTERNAL_SERVER_ERROR, message);
    }

    /**
     * @return the vroom error code carried in the JSON body
     */
    public int code() {
        return code;
    }

    /**
     * @return the HTTP status to answer with
     */
    public HttpStatus httpStatus() {
        return status;
    }
}
