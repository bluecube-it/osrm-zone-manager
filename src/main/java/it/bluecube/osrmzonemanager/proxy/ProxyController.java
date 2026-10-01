package it.bluecube.osrmzonemanager.proxy;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ProxyController {

    private final ProxyService proxyService;

    /**
     * Proxies OSRM-related requests to the appropriate zone-based service.
     * This method handles both GET and POST HTTP methods and dynamically
     * constructs the forwarded request path and query parameters.
     *
     * @param zoneId  the identifier of the zone to which the request should be forwarded
     * @param request the original HttpServletRequest containing request details such as headers, query parameters, and body
     * @return a ResponseEntity containing the response from the proxied OSRM service
     */
    @RequestMapping(value = "/{zoneId}/osrm/**", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> proxyOsrm(@PathVariable String zoneId, HttpServletRequest request) {
        var path = ProxyPaths.stripPrefix(request, "/" + zoneId + "/osrm/");
        String newQuery = proxyService.buildOsrmQuery(request, path);
        return proxyService.forwardToZone(zoneId, request, path, newQuery);
    }
}
