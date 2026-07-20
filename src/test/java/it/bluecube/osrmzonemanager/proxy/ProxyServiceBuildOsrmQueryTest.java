package it.bluecube.osrmzonemanager.proxy;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.zone.ZoneService;
import it.bluecube.test.BaseUnitTest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

class ProxyServiceBuildOsrmQueryTest extends BaseUnitTest {

    @Mock
    private OsrmZoneManagerConfig config;
    @Mock
    private ZoneService zoneService;
    @Mock
    private RestClient proxyRestClient;

    private RadiusesMiddleware radiusesMiddleware;
    private ProxyService target;

    @BeforeEach
    void setUp() {
        radiusesMiddleware = new RadiusesMiddleware();
        target = new ProxyService(config, radiusesMiddleware, proxyRestClient, zoneService);
        Mockito.when(config.getOsrmDefaultRadius()).thenReturn(50);
    }

    @Test
    void shouldInjectDefaultRadiusesForRoutePath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/activezone1234/osrm/route/v1/driving/0,0;1,1");

        String query = target.buildOsrmQuery(request, "route/v1/driving/0,0;1,1");

        Assertions.assertThat(query).isEqualTo("radiuses=50;50");
    }

    @Test
    void shouldPassThroughQueryForNonRoutePath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/activezone1234/osrm/nearest/v1/driving/0,0");
        request.setQueryString("overview=false");

        String query = target.buildOsrmQuery(request, "nearest/v1/driving/0,0");

        Assertions.assertThat(query).isEqualTo("overview=false");
    }

    @Test
    void shouldUseHeaderRadiusOverride() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/activezone1234/osrm/route/v1/driving/0,0;1,1");
        request.addHeader("x-osrm-radius", "123");

        String query = target.buildOsrmQuery(request, "route/v1/driving/0,0;1,1");

        Assertions.assertThat(query).isEqualTo("radiuses=123;123");
    }
}
