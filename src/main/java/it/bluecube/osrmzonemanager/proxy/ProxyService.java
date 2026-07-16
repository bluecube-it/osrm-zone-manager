package it.bluecube.osrmzonemanager.proxy;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.zone.ZoneDTO;
import it.bluecube.osrmzonemanager.zone.ZoneService;
import it.bluecube.osrmzonemanager.zone.ZoneStatus;
import it.bluecube.osrmzonemanager.zone.ZoneUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.ConnectException;
import java.util.Enumeration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyService {
    private static final List<String> HOP_HEADERS = List.of(HttpHeaders.HOST, HttpHeaders.TRANSFER_ENCODING,
            HttpHeaders.CONNECTION, "keep-alive", HttpHeaders.PROXY_AUTHENTICATE,
            HttpHeaders.PROXY_AUTHORIZATION, HttpHeaders.TE, "trailers", HttpHeaders.UPGRADE);
    private static final String HEADER_X_OSRM_RADIUS = "x-osrm-radius";
    private static final String LOCALHOST = "127.0.0.1";
    private static final String HTTP_SCHEME = "http://";
    private static final String ACCEPT_ENCODING_IDENTITY = "identity";

    private final OsrmZoneManagerConfig config;
    private final RadiusesMiddleware radiusesMiddleware;
    private final RestClient proxyRestClient;
    private final ZoneService zoneService;

    private boolean isHopHeader(String headerName) {
        return HOP_HEADERS.stream().anyMatch(hop -> hop.equalsIgnoreCase(headerName));
    }

    public String buildOsrmQuery(HttpServletRequest request, String path) {
        String queryString = request.getQueryString();
        Integer headerRadius = parseRadiusHeader(request.getHeader(HEADER_X_OSRM_RADIUS));

        return radiusesMiddleware.injectRadiusesQuery(
                queryString == null ? "" : queryString,
                "/" + path,
                config.getOsrmDefaultRadius(),
                headerRadius
        );
    }

    private Integer parseRadiusHeader(String radiusHeader) {
        if (radiusHeader == null) {
            return null;
        }
        try {
            return Integer.parseInt(radiusHeader);
        } catch (NumberFormatException _) {
            return null;
        }
    }

    public ResponseEntity<byte[]> forwardToZone(String zoneId, ProxyType service, HttpServletRequest request,
                                                String path, String query) {
        ZoneDTO dto = zoneService.findZone(zoneId);
        ZoneStatus status = dto.status();
        if (status != ZoneStatus.ACTIVE && status != ZoneStatus.DEGRADED) {
            throw new ZoneUnavailableException(zoneId, status != null ? status.name() : "unknown", dto.error());
        }
        zoneService.touch(zoneId);

        Integer port = ProxyType.OSRM.equals(service) ? dto.osrmPort() : dto.vroomPort();
        if (port == null) {
            throw new ProxyTargetUnreachableException(service.name() + " target not configured for zone " + zoneId);
        }
        return forward(request, service, port, path, query);
    }

    private ResponseEntity<byte[]> forward(HttpServletRequest request, ProxyType service, int port, String path, String query) {
        String target = buildTargetUrl(port, path, query);
        HttpHeaders headers = buildHeaders(request, port);
        HttpMethod method = HttpMethod.valueOf(request.getMethod());

        try {
            byte[] requestBody = method == HttpMethod.POST ? request.getInputStream().readAllBytes() : null;
            ResponseEntity<byte[]> response = executeRequest(method, target, headers, requestBody);
            return ResponseEntity.status(response.getStatusCode())
                    .headers(filterResponseHeaders(response.getHeaders()))
                    .body(response.getBody());
        } catch (ResourceAccessException e) {
            throw translateConnectionError(e, service, port);
        } catch (IOException e) {
            throw new ProxyException("Proxy error: " + e.getMessage());
        }
    }

    private RuntimeException translateConnectionError(ResourceAccessException e, ProxyType service, int port) {
        if (e.getCause() instanceof ConnectException) {
            return new ProxyTargetUnreachableException(
                    service.name() + " unreachable at " + LOCALHOST + ":" + port + ": " + e.getMessage());
        }
        return new ProxyException(service.name() + " error: " + e.getMessage());
    }

    private String buildTargetUrl(int port, String path, String query) {
        String target = HTTP_SCHEME + LOCALHOST + ":" + port + "/" + path;
        if (query != null && !query.isEmpty()) {
            target += "?" + query;
        }
        return target;
    }

    private ResponseEntity<byte[]> executeRequest(HttpMethod method, String target, HttpHeaders headers, byte[] body) {
        var spec = proxyRestClient.method(method)
                .uri(target)
                .headers(h -> h.addAll(headers));
        return body != null
                ? spec.body(body).retrieve().toEntity(byte[].class)
                : spec.retrieve().toEntity(byte[].class);
    }

    private HttpHeaders filterResponseHeaders(HttpHeaders source) {
        HttpHeaders result = new HttpHeaders();
        source.forEach((key, values) -> {
            if (!isHopHeader(key)
                    && !key.equalsIgnoreCase(HttpHeaders.CONTENT_ENCODING)
                    && !key.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH)) {
                result.put(key, values);
            }
        });
        return result;
    }

    private HttpHeaders buildHeaders(HttpServletRequest request, int port) {
        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (isHopHeader(name) || name.equalsIgnoreCase(HttpHeaders.ACCEPT_ENCODING)) {
                continue;
            }
            copyHeaderValues(request, name, headers);
        }
        headers.set(HttpHeaders.HOST, LOCALHOST + ":" + port);
        headers.set(HttpHeaders.ACCEPT_ENCODING, ACCEPT_ENCODING_IDENTITY);
        return headers;
    }

    private void copyHeaderValues(HttpServletRequest request, String name, HttpHeaders headers) {
        Enumeration<String> values = request.getHeaders(name);
        while (values.hasMoreElements()) {
            headers.add(name, values.nextElement());
        }
    }
}
