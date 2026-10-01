package it.bluecube.osrmzonemanager.proxy;

import com.github.tomakehurst.wiremock.client.WireMock;
import it.bluecube.osrmzonemanager.global.GlobalOsrmService;
import it.bluecube.osrmzonemanager.global.GlobalOsrmStatusDTO;
import it.bluecube.osrmzonemanager.zone.ZoneProfile;
import it.bluecube.test.integration_test.BaseIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.Optional;

class GlobalProxyControllerProxyOsrmIT extends BaseIT {

    @MockitoBean
    private GlobalOsrmService globalOsrmService;

    @Value("${wiremock.server.port}")
    private int wireMockPort;

    @BeforeEach
    void setUp() {
        Mockito.when(globalOsrmService.findPort(ZoneProfile.CAR)).thenReturn(Optional.of(wireMockPort));
        Mockito.when(globalOsrmService.findPort(ZoneProfile.BUS)).thenReturn(Optional.empty());
        Mockito.when(globalOsrmService.statusOf(ZoneProfile.BUS)).thenReturn(
                it.bluecube.osrmzonemanager.global.GlobalOsrmStatus.BUILDING);
        Mockito.when(globalOsrmService.errorOf(ZoneProfile.BUS)).thenReturn(null);
    }

    @Test
    void shouldProxyToGlobalInstanceWithRadiusesInjected() {
        WireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/route/v1/driving/0,0;1,1"))
                .withQueryParam("radiuses", WireMock.equalTo("50;50"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withBody("{\"global\":true}")));

        restTestClient.get()
                .uri("/osrm/car/route/v1/driving/0,0;1,1")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("{\"global\":true}");
    }

    @Test
    void shouldAcceptProfileCaseInsensitively() {
        WireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/route/v1/driving/0,0;1,1"))
                .willReturn(WireMock.aResponse().withStatus(200).withBody("case-ok")));

        restTestClient.get()
                .uri("/osrm/CAR/route/v1/driving/0,0;1,1")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("case-ok");
    }

    @Test
    void shouldProxyPostToGlobalInstance() {
        WireMock.stubFor(WireMock.post(WireMock.urlPathEqualTo("/table/v1/driving/0,0;1,1"))
                .withQueryParam("radiuses", WireMock.equalTo("50;50"))
                .willReturn(WireMock.aResponse().withStatus(200).withBody("table-ok")));

        restTestClient.post()
                .uri("/osrm/car/table/v1/driving/0,0;1,1")
                .body(Map.of("sources", List.of(0)))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("table-ok");
    }

    @Test
    void shouldReturnBadRequestForUnknownProfile() {
        restTestClient.get()
                .uri("/osrm/truck/route/v1/driving/0,0;1,1")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").exists();
    }

    @Test
    void shouldReturnServiceUnavailableWhileProfileIsNotServing() {
        restTestClient.get()
                .uri("/osrm/bus/route/v1/driving/0,0;1,1")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").value(
                        error -> org.assertj.core.api.Assertions.assertThat(String.valueOf(error))
                                .contains("BUILDING"));
    }

    @Test
    void shouldListGlobalProfileStatuses() {
        Mockito.when(globalOsrmService.statuses()).thenReturn(List.of(
                new GlobalOsrmStatusDTO("CAR", "READY", 5010, null),
                new GlobalOsrmStatusDTO("BUS", "BUILDING", null, null)));

        restTestClient.get()
                .uri("/osrm")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].profile").isEqualTo("CAR")
                .jsonPath("$[1].status").isEqualTo("BUILDING");
    }
}
