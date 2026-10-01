package it.bluecube.osrmzonemanager.vroom;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * VROOM endpoints, served in-process: the request body is piped to the {@code vroom} binary instead of
 * being proxied to a per-zone {@code vroom-express} HTTP server.
 *
 * <p>Routes mirror the previous proxy layout: {@code POST /{zoneId}/vroom} (with or without trailing slash)
 * and {@code GET /{zoneId}/vroom/health}.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class VroomController {

    private final VroomService vroomService;

    /**
     * Solves a VROOM problem for the zone.
     *
     * @param zoneId  zone identifier
     * @param request incoming request; body is forwarded to the binary's stdin
     * @return the binary's stdout with the status mapped from its exit code
     */
    @RequestMapping(value = {"/{zoneId}/vroom", "/{zoneId}/vroom/"}, method = RequestMethod.POST)
    public ResponseEntity<byte[]> solve(@PathVariable String zoneId, HttpServletRequest request) {
        try {
            return vroomService.solve(zoneId, request.getInputStream());
        } catch (java.io.IOException e) {
            throw VroomErrorException.input("could not read request body: " + e.getMessage());
        }
    }

    /**
     * Probes the zone's VROOM binary.
     *
     * @param zoneId zone identifier
     * @return 200 with an empty body when healthy, 500 otherwise
     */
    @RequestMapping(value = "/{zoneId}/vroom/health", method = RequestMethod.GET)
    public ResponseEntity<byte[]> health(@PathVariable String zoneId) {
        return vroomService.health(zoneId);
    }
}
