package it.bluecube.osrmzonemanager.proxy;

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

import com.github.tomakehurst.wiremock.client.WireMock;

class ProxyControllerProxyVroomIT extends BaseIT {

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
    void shouldProxyVroomGetRequest() {
        String zoneId = saveActiveZone();
        WireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/health"))
                .withQueryParam("ping", WireMock.equalTo("true"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withBody("vroom-ok")));

        restTestClient.get()
                .uri("/{zoneId}/vroom/health?ping=true", zoneId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("vroom-ok");
    }

    @Test
    void shouldReturnNotFoundForNonExistentZone() {
        restTestClient.get()
                .uri("/{zoneId}/vroom/health", "nonexistent123")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").exists();
    }

    @Test
    void shouldReturnServiceUnavailableForBuildingZone() {
        String zoneId = "buildingvroom12";
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId(zoneId)
                .status(ZoneStatus.BUILDING.name())
                .osrmPort(wireMockPort)
                .vroomPort(wireMockPort)
                .build();
        zoneRepository.save(zone);

        restTestClient.get()
                .uri("/{zoneId}/vroom/health", zoneId)
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.error").exists();
    }

    private String saveActiveZone() {
        String zoneId = "activevroom123";
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
