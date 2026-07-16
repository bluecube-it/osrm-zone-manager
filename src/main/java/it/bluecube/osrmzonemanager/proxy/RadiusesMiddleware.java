package it.bluecube.osrmzonemanager.proxy;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Middleware that injects an OSRM {@code radiuses} query parameter into
 * {@code /route} and {@code /table} requests, so that coordinates are
 * snapped to the road network within a configurable radius instead of
 * failing with {@code NoSegment} errors.
 *
 * <p>If the client already supplies a {@code radiuses} parameter, it is
 * validated against the number of coordinates in the path and left
 * untouched; otherwise a default (or header-provided) radius is repeated
 * for every coordinate.
 *
 * <p>Coordinate segments may be plain ({@code lat,lon;lat,lon}) or encoded as
 * polyline5 / polyline6. The polyline is not fully decoded — only the point
 * count is extracted to size the {@code radiuses} array.
 */
@Component
public class RadiusesMiddleware {

    private static final Pattern ROUTE_OR_TABLE = Pattern.compile("^/(route|table)/v1/[^/]+/(.+)$");
    private static final String POLYLINE6_PREFIX = "polyline6(";
    private static final String POLYLINE_PREFIX = "polyline(";
    private static final String QUERY_PARAM_RADIUS = "radiuses";

    /**
     * Counts the number of points in an encoded polyline string.
     *
     * @param encoded the polyline payload (without the {@code polyline(...)} wrapper)
     * @return the number of decoded coordinate pairs
     */
    private static int decodePolyline(String encoded) {
        int[] cursor = {0};
        int count = 0;
        while (cursor[0] < encoded.length()) {
            decodeNext(encoded, cursor);
            decodeNext(encoded, cursor);
            count++;
        }
        return count;
    }

    /**
     * Advances the cursor past one variable-length signed value in an encoded polyline.
     *
     * @param encoded the polyline string
     * @param cursor a single-element array holding the current position; updated in place
     * @throws IllegalArgumentException if the string ends unexpectedly or contains an invalid character
     */
    private static void decodeNext(String encoded, int[] cursor) {
        int index = cursor[0];
        int b;
        do {
            if (index >= encoded.length()) {
                throw new IllegalArgumentException("unexpected end of polyline string");
            }
            char ch = encoded.charAt(index++);
            if (ch < '?' || ch > '~') {
                throw new IllegalArgumentException("invalid polyline character: " + ch);
            }
            b = ch - 63;
        } while (b >= 0x20);
        cursor[0] = index;
    }

    /**
     * Extracts the coordinate segment from an OSRM route/table path.
     *
     * @param path the request path
     * @return an {@link Optional} containing the parsed {@link RouteInfo},
     *         or empty if the path doesn't match the route/table pattern
     */
    public Optional<RouteInfo> extractRouteInfo(String path) {
        if (path == null) {
            return Optional.empty();
        }
        Matcher m = ROUTE_OR_TABLE.matcher(path);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new RouteInfo(m.group(2)));
    }

    /**
     * Counts coordinates in a plain (non-polyline) coordinate string, e.g. {@code "1,2;3,4"}.
     *
     * @param coordPart the coordinate segment of the path
     * @return the number of coordinates, or {@code 0} if none/empty
     */
    public int countCoords(String coordPart) {
        if (coordPart == null || coordPart.isEmpty()) {
            return 0;
        }
        if (coordPart.contains(";")) {
            return coordPart.split(";").length;
        }
        return coordPart.contains(",") ? 1 : 0;
    }

    /**
     * Counts coordinates in a coordinate segment, supporting {@code polyline(...)},
     * {@code polyline6(...)}, and plain coordinate lists.
     *
     * @param coordPart the coordinate segment of the path
     * @return the number of coordinates encoded, or {@code 0} if none/empty
     * @throws PolylineDecodeException if a polyline segment cannot be decoded
     */
    public int countCoordsWithPolyline(String coordPart) {
        if (coordPart == null || coordPart.isEmpty()) {
            return 0;
        }
        if (coordPart.startsWith(POLYLINE6_PREFIX)) {
            return decodePolylineCount(coordPart, POLYLINE6_PREFIX);
        }
        if (coordPart.startsWith(POLYLINE_PREFIX)) {
            return decodePolylineCount(coordPart, POLYLINE_PREFIX);
        }
        return countCoords(coordPart);
    }

    /**
     * Injects a {@code radiuses} query parameter into an OSRM route/table request.
     *
     * <p>If the path isn't a route/table request, or has no coordinates, the query
     * is returned unchanged. If the client already provided {@code radiuses}, it must
     * match the coordinate count or an {@link IllegalArgumentException} is thrown.
     *
     * @param originalQuery the original query string (without leading {@code ?}), may be {@code null}
     * @param path          the request path
     * @param defaultRadius radius (in meters) used when no header radius is given
     * @param headerRadius  optional radius override from a request header
     * @return the query string with {@code radiuses} injected (or unchanged if not applicable)
     * @throws IllegalArgumentException if the client-supplied radiuses count doesn't match the coordinate count
     * @throws PolylineDecodeException if the path contains a malformed polyline segment
     */
    public String injectRadiusesQuery(String originalQuery, String path, int defaultRadius, Integer headerRadius) {
        Optional<RouteInfo> info = extractRouteInfo(path);
        if (info.isEmpty()) {
            return originalQuery;
        }

        int coordCount = countCoordsWithPolyline(info.get().coordsString());
        if (coordCount <= 0) {
            return originalQuery;
        }

        int radius = headerRadius != null ? headerRadius : defaultRadius;

        String clientRadiuses = extractQueryParam(originalQuery, QUERY_PARAM_RADIUS);
        String radiusesStr;
        if (clientRadiuses != null) {
            int clientCount = clientRadiuses.isEmpty() ? 0 : clientRadiuses.split(";").length;
            if (clientCount != coordCount) {
                throw new IllegalArgumentException(
                        "client radiuses has " + clientCount + " entries but path has " + coordCount + " coordinates"
                );
            }
            radiusesStr = clientRadiuses;
        } else {
            radiusesStr = String.join(";", Collections.nCopies(coordCount, String.valueOf(radius)));
        }

        if (originalQuery == null || originalQuery.isEmpty()) {
            return QUERY_PARAM_RADIUS + "=" + radiusesStr;
        }

        String stripped = removeQueryParam(originalQuery, QUERY_PARAM_RADIUS);
        return stripped.isEmpty() ? QUERY_PARAM_RADIUS + "=" + radiusesStr : stripped + "&" + QUERY_PARAM_RADIUS + "=" + radiusesStr;
    }

    /**
     * Extracts and counts the points in a {@code polyline(...)} / {@code polyline6(...)} segment.
     *
     * @param coordPart the full coordinate segment, including the prefix and closing parenthesis
     * @param prefix   the polyline variant prefix ({@link #POLYLINE_PREFIX} or {@link #POLYLINE6_PREFIX})
     * @return the number of decoded coordinate pairs, or {@code 0} if the segment is empty
     * @throws PolylineDecodeException if the encoded payload is malformed
     */
    private int decodePolylineCount(String coordPart, String prefix) {
        String encoded = coordPart.substring(prefix.length());
        if (encoded.endsWith(")")) {
            encoded = encoded.substring(0, encoded.length() - 1);
        }
        if (encoded.isEmpty()) {
            return 0;
        }
        try {
            return decodePolyline(encoded);
        } catch (RuntimeException e) {
            String preview = encoded.substring(0, Math.min(encoded.length(), 40));
            throw new PolylineDecodeException(
                    "polyline decode failed: " + e.getMessage() + " (input: " + preview + "...)", e
            );
        }
    }

    /**
     * Returns the raw value of a query parameter, or {@code null} if absent.
     *
     * @param query the query string (without leading {@code ?})
     * @param name  the parameter name
     * @return the first matching parameter value, or {@code null} if not present
     */
    private String extractQueryParam(String query, String name) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        String prefix = name + "=";
        return Arrays.stream(query.split("&"))
                .filter(segment -> segment.startsWith(prefix))
                .map(segment -> segment.substring(prefix.length()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the query string with all occurrences of the given parameter removed.
     *
     * @param query the query string (without leading {@code ?})
     * @param name  the parameter name to strip
     * @return the cleaned query string, or empty if nothing remains
     */
    private String removeQueryParam(String query, String name) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        String prefix = name + "=";
        return Arrays.stream(query.split("&"))
                .filter(segment -> !segment.startsWith(prefix))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    /**
     * Parsed OSRM path info.
     *
     * @param coordsString the raw coordinate segment of the path (plain coordinates or a polyline)
     */
    public record RouteInfo(String coordsString) {
    }
}