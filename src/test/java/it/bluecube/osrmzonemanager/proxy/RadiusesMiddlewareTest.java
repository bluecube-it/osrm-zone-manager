package it.bluecube.osrmzonemanager.proxy;

import it.bluecube.test.BaseUnitTest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


class RadiusesMiddlewareTest extends BaseUnitTest {

    private RadiusesMiddleware middleware;

    @BeforeEach
    void setUp() {
        middleware = new RadiusesMiddleware();
    }

    @Test
    void testExtractRouteInfoRoute() {
        var info = middleware.extractRouteInfo("/route/v1/driving/10,20;30,40");
        Assertions.assertThat(info).isPresent();
        Assertions.assertThat(info.get().coordsString()).isEqualTo("10,20;30,40");
    }

    @Test
    void testExtractRouteInfoTable() {
        var info = middleware.extractRouteInfo("/table/v1/walking/a;b;c");
        Assertions.assertThat(info).isPresent();
        Assertions.assertThat(info.get().coordsString()).isEqualTo("a;b;c");
    }

    @Test
    void testExtractRouteInfoNonMatch() {
        var info = middleware.extractRouteInfo("/health");
        Assertions.assertThat(info).isEmpty();
    }

    @Test
    void testCountRawSemicolon() {
        Assertions.assertThat(middleware.countCoords("13.38,52.51;13.39,52.52;13.40,52.53")).isEqualTo(3);
        Assertions.assertThat(middleware.countCoords("10,20;30,40")).isEqualTo(2);
    }

    @Test
    void testCountRawSingle() {
        Assertions.assertThat(middleware.countCoords("10.0,20.0")).isEqualTo(1);
    }

    @Test
    void testCountEmpty() {
        Assertions.assertThat(middleware.countCoords("")).isEqualTo(0);
        Assertions.assertThat(middleware.countCoords("polyline(abc)")).isEqualTo(0);
    }

    @Test
    void testNonRoutePassthrough() {
        String result = middleware.injectRadiusesQuery("", "/nearest/v1/driving/10,20", 50, null);
        Assertions.assertThat(result).isEqualTo("");
    }

    @Test
    void testNonRouteWithQueryPassthrough() {
        String result = middleware.injectRadiusesQuery("ping=true", "/health", 50, null);
        Assertions.assertThat(result).isEqualTo("ping=true");
    }

    @Test
    void testInjectDefaultRadiusSemicolonCoords() {
        String result = middleware.injectRadiusesQuery("overview=false", "/route/v1/driving/10,20;30,40", 50, null);
        Assertions.assertThat(result).contains("radiuses=50;50");
    }

    @Test
    void testInjectCustomRadius() {
        String result = middleware.injectRadiusesQuery("", "/table/v1/driving/1;2;3", 200, null);
        Assertions.assertThat(result).isEqualTo("radiuses=200;200;200");
    }

    @Test
    void testInjectHeaderOverride() {
        String result = middleware.injectRadiusesQuery("", "/route/v1/driving/1;2", 50, 200);
        Assertions.assertThat(result).isEqualTo("radiuses=200;200");
    }

    @Test
    void testClientRadiusesPreservedMatchingCount() {
        String result = middleware.injectRadiusesQuery("radiuses=30;30&t=true", "/route/v1/driving/10,20;30,40", 50, null);
        Assertions.assertThat(result).contains("radiuses=30;30");
    }

    @Test
    void testMismatchedRadiusesThrowsValueError() {
        Assertions.assertThatThrownBy(() ->
                        middleware.injectRadiusesQuery("radiuses=50", "/route/v1/driving/10,20;30,40", 50, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entries")
                .hasMessageContaining("coordinates");
    }

    @Test
    void testSingleCoordInjectsSingleRadius() {
        String result = middleware.injectRadiusesQuery("", "/route/v1/driving/10,20", 75, null);
        Assertions.assertThat(result).isEqualTo("radiuses=75");
        Assertions.assertThat(result).doesNotContain("radiuses=75;75");
    }

    @Test
    void testCountCoordsWithPolylineRawFallsThrough() {
        Assertions.assertThat(middleware.countCoordsWithPolyline("10,20;30,40;50,60")).isEqualTo(3);
    }

    @Test
    void testPolyline5Decoding() {
        // Google example polyline for 3 points
        Assertions.assertThat(middleware.countCoordsWithPolyline("polyline(_p~iF~ps|U_ulLnnqC_mqNvxq`@)")).isEqualTo(3);
    }

    @Test
    void testPolyline6Decoding() {
        double[][] points = {{38.5, -120.2}, {40.7, -120.95}, {43.252, -126.453}};
        String encoded = encodePolyline(points, 1_000_000.0);
        Assertions.assertThat(middleware.countCoordsWithPolyline("polyline6(" + encoded + ")")).isEqualTo(3);
    }

    @Test
    void testInvalidPolylineDecodeThrowsPolylineDecodeException() {
        Assertions.assertThatThrownBy(() ->
                        middleware.countCoordsWithPolyline("polyline(!!!invalid!!!)"))
                .isInstanceOf(PolylineDecodeException.class)
                .hasMessageContaining("decode failed");
    }

    @Test
    void testDecodeErrorPropagatesInInjectRadiuses() {
        Assertions.assertThatThrownBy(() ->
                        middleware.injectRadiusesQuery("", "/route/v1/driving/polyline(bad!@#)", 50, null))
                .isInstanceOf(PolylineDecodeException.class)
                .hasMessageContaining("decode");
    }

    @Test
    void testPolylineDecodeExceptionIsIllegalArgumentException() {
        Assertions.assertThat(new PolylineDecodeException("test"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String encodePolyline(double[][] points, double precision) {
        StringBuilder sb = new StringBuilder();
        int lastLat = 0;
        int lastLng = 0;
        for (double[] point : points) {
            int lat = (int) Math.round(point[0] * precision);
            int lng = (int) Math.round(point[1] * precision);
            encodeValue(sb, lat - lastLat);
            encodeValue(sb, lng - lastLng);
            lastLat = lat;
            lastLng = lng;
        }
        return sb.toString();
    }

    private void encodeValue(StringBuilder sb, int value) {
        value = value < 0 ? ~(value << 1) : (value << 1);
        while (value >= 0x20) {
            sb.append((char) ((0x20 | (value & 0x1f)) + 63));
            value >>= 5;
        }
        sb.append((char) (value + 63));
    }
}
