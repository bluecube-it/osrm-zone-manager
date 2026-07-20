package it.bluecube.osrmzonemanager.proxy;

import com.github.tomakehurst.wiremock.client.WireMock;
import it.bluecube.osrmzonemanager.maps.MapsService;
import it.bluecube.osrmzonemanager.zone.ZoneEntity;
import it.bluecube.osrmzonemanager.zone.ZoneRepository;
import it.bluecube.osrmzonemanager.zone.ZoneStatus;
import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

class ProxyControllerProxyOsrmIT extends BaseIT {

    @Autowired
    private ZoneRepository zoneRepository;

    @MockitoBean
    private MapsService mapsService;

    @Value("${wiremock.server.port}")
    private int wireMockPort;

    @BeforeEach
    void setUp() throws Exception {
        Mockito.when(mapsService.ensureBasePbf()).thenReturn("/tmp/base.pbf");
    }

    @Test
    void shouldProxyOsrmGetRequest() {
        String zoneId = saveActiveZone();
        WireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/route/v1/driving/0,0;1,1"))
                .withQueryParam("radiuses", WireMock.equalTo("50;50"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true}")));

        restTestClient.get()
                .uri("/{zoneId}/osrm/route/v1/driving/0,0;1,1", zoneId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("{\"ok\":true}");
    }

    @Test
    void shouldProxyOsrmPostRequest() {
        String zoneId = saveActiveZone();
        WireMock.stubFor(WireMock.post(WireMock.urlPathEqualTo("/route/v1/driving/0,0;1,1"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withBody("post-ok")));

        restTestClient.post()
                .uri("/{zoneId}/osrm/route/v1/driving/0,0;1,1", zoneId)
                .body(Map.of("coordinates", "0,0;1,1"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("post-ok");
    }

    @Test
    void shouldInjectHeaderRadiusIntoOsrmQuery() {
        String zoneId = saveActiveZone();
        WireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/route/v1/driving/0,0;1,1"))
                .withQueryParam("radiuses", WireMock.equalTo("200;200"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withBody("radius-ok")));

        restTestClient.get()
                .uri("/{zoneId}/osrm/route/v1/driving/0,0;1,1", zoneId)
                .header("x-osrm-radius", "200")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("radius-ok");
    }

    @Test
    void shouldReturnNotFoundForNonExistentZone() {
        restTestClient.get()
                .uri("/{zoneId}/osrm/route/v1/driving/0,0;1,1", "nonexistent123")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").exists();
    }

    @Test
    void shouldReturnServiceUnavailableForBuildingZone() {
        String zoneId = "buildingzone12";
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId(zoneId)
                .status(ZoneStatus.BUILDING.name())
                .osrmPort(wireMockPort)
                .vroomPort(wireMockPort)
                .build();
        zoneRepository.save(zone);

        restTestClient.get()
                .uri("/{zoneId}/osrm/route/v1/driving/0,0;1,1", zoneId)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error").exists();
    }

    private String saveActiveZone() {
        String zoneId = "activezone1234";
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId(zoneId)
                .status(ZoneStatus.ACTIVE.name())
                .osrmPort(wireMockPort)
                .vroomPort(wireMockPort)
                .build();
        zoneRepository.save(zone);
        return zoneId;
    }
}
