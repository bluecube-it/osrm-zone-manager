package it.bluecube.osrmzonemanager;

import it.bluecube.osrmzonemanager.builder.BuildException;
import it.bluecube.osrmzonemanager.maps.MissingBasePbfException;
import it.bluecube.osrmzonemanager.proxy.PolylineDecodeException;
import it.bluecube.osrmzonemanager.proxy.ProxyException;
import it.bluecube.osrmzonemanager.proxy.ProxyTargetUnreachableException;
import it.bluecube.osrmzonemanager.zone.ZoneInProgressException;
import it.bluecube.osrmzonemanager.zone.ZoneNotFoundException;
import it.bluecube.osrmzonemanager.zone.ZoneUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Centralizes HTTP translation for all domain exceptions.
 * Controllers stay clean — no try/catch for status mapping.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ZoneNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(ZoneNotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ZoneInProgressException.class)
    public ResponseEntity<Map<String, String>> handleConflict(ZoneInProgressException e) {
        String msg = e.getMessage() + " — poll GET /zones/" + e.zoneId();
        return body(HttpStatus.CONFLICT, msg);
    }

    @ExceptionHandler(ZoneUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleUnavailable(ZoneUnavailableException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(MissingBasePbfException.class)
    public ResponseEntity<Map<String, String>> handleMissingBasePbf(MissingBasePbfException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(ProxyTargetUnreachableException.class)
    public ResponseEntity<Map<String, String>> handleBadGateway(ProxyTargetUnreachableException e) {
        return body(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(ProxyException.class)
    public ResponseEntity<Map<String, String>> handleProxyError(ProxyException e) {
        return body(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(PolylineDecodeException.class)
    public ResponseEntity<Map<String, String>> handlePolylineError(PolylineDecodeException e) {
        return body(HttpStatus.BAD_REQUEST, "polyline decode failed: " + e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(BuildException.class)
    public ResponseEntity<Map<String, String>> handleBuildException(BuildException e) {
        log.error("Build failure: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        log.warn("Concurrent modification conflict: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "concurrent modification — retry the request"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericException(Exception e) {
        log.error("Unexpected error: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "internal server error"));
    }

    private ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
