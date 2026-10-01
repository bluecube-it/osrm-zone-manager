package it.bluecube.osrmzonemanager.proxy;

import it.bluecube.osrmzonemanager.global.GlobalOsrmService;
import it.bluecube.osrmzonemanager.global.GlobalOsrmStatusDTO;
import it.bluecube.osrmzonemanager.global.GlobalOsrmUnavailableException;
import it.bluecube.osrmzonemanager.zone.ZoneProfile;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Proxy for the global whole-map OSRM instances, one per routing profile, started at boot from the
 * base PBF ({@link GlobalOsrmService}).
 *
 * <p>{@code /osrm/{profile}/**} forwards to the profile's {@code osrm-routed} with the same radiuses
 * injection as the zone proxy. The profile is validated against {@link ZoneProfile} (case-insensitive;
 * unknown values yield HTTP 400), and requests are rejected with HTTP 503 until the profile is ready.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class GlobalProxyController {

    private static final String GLOBAL_PREFIX = "/osrm/";

    private final GlobalOsrmService globalOsrmService;
    private final ProxyService proxyService;

    /**
     * @return the status of every global profile (empty when globally disabled)
     */
    @GetMapping("/osrm")
    public ResponseEntity<List<GlobalOsrmStatusDTO>> listGlobalProfiles() {
        return ResponseEntity.ok(globalOsrmService.statuses());
    }

    /**
     * Proxies a request to the global OSRM instance of the requested routing profile.
     *
     * @param profile profile name ({@code CAR}, {@code BUS}, case-insensitive)
     * @param request the original request
     * @return the OSRM response
     * @throws IllegalArgumentException       if the profile is unknown (HTTP 400)
     * @throws GlobalOsrmUnavailableException if the profile is not serving yet (HTTP 503)
     */
    @RequestMapping(value = GLOBAL_PREFIX + "{profile}/**", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> proxyGlobalOsrm(@PathVariable String profile, HttpServletRequest request) {
        ZoneProfile zoneProfile = ZoneProfile.fromValue(profile);
        Integer port = globalOsrmService.findPort(zoneProfile)
                .orElseThrow(() -> new GlobalOsrmUnavailableException(
                        profile,
                        String.valueOf(globalOsrmService.statusOf(zoneProfile)),
                        globalOsrmService.errorOf(zoneProfile)));

        String path = ProxyPaths.stripPrefix(request, GLOBAL_PREFIX + profile + "/");
        String query = proxyService.buildOsrmQuery(request, path);
        return proxyService.forwardToPort(port, request, path, query);
    }
}
