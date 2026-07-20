package it.bluecube.osrmzonemanager.proxy;

import com.github.tomakehurst.wiremock.client.WireMock;
import it.bluecube.osrmzonemanager.zone.ZoneDTO;
import it.bluecube.osrmzonemanager.zone.ZoneService;
import it.bluecube.osrmzonemanager.zone.ZoneStatus;
import it.bluecube.osrmzonemanager.zone.ZoneUnavailableException;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class ProxyServiceForwardToZoneIT extends BaseIT {

    @Autowired
    private ProxyService target;

    @MockitoBean
    private ZoneService zoneService;

    @Value("${wiremock.server.port}")
    private int wireMockPort;

    @Test
    void shouldForwardToOsrmPort() {
        String zoneId = "zoneosrm123456";
        ZoneDTO dto = activeDto(zoneId, wireMockPort, wireMockPort);
        Mockito.when(zoneService.findZone(zoneId)).thenReturn(dto);

        WireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/route/v1/driving/0,0;1,1"))
                .willReturn(WireMock.aResponse().withStatus(200).withBody("osrm-body")));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/" + zoneId + "/osrm/route/v1/driving/0,0;1,1");
        ResponseEntity<byte[]> response = target.forwardToZone(zoneId, ProxyType.OSRM, request,
                "route/v1/driving/0,0;1,1", "radiuses=50;50");

        Assertions.assertThat(response.getStatusCode().value()).isEqualTo(200);
        Assertions.assertThat(response.getBody()).isEqualTo("osrm-body".getBytes());
    }

    @Test
    void shouldForwardToVroomPort() {
        String zoneId = "zonevroom123456";
        ZoneDTO dto = activeDto(zoneId, wireMockPort, wireMockPort);
        Mockito.when(zoneService.findZone(zoneId)).thenReturn(dto);

        WireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/health"))
                .willReturn(WireMock.aResponse().withStatus(200).withBody("vroom-body")));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/" + zoneId + "/vroom/health");
        ResponseEntity<byte[]> response = target.forwardToZone(zoneId, ProxyType.VROOM, request,
                "health", "ping=true");

        Assertions.assertThat(response.getStatusCode().value()).isEqualTo(200);
        Assertions.assertThat(response.getBody()).isEqualTo("vroom-body".getBytes());
    }

    @Test
    void shouldThrowZoneUnavailableExceptionForNonActiveZone() {
        String zoneId = "zonebuild12345";
        ZoneDTO dto = ZoneDTO.builder()
                .zoneId(zoneId)
                .status(ZoneStatus.BUILDING)
                .osrmPort(5001)
                .vroomPort(3001)
                .build();
        Mockito.when(zoneService.findZone(zoneId)).thenReturn(dto);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/" + zoneId + "/osrm/route/v1/driving/0,0;1,1");

        Assertions.assertThatThrownBy(() -> target.forwardToZone(zoneId, ProxyType.OSRM, request, "route/v1/driving/0,0;1,1", ""))
                .isInstanceOf(ZoneUnavailableException.class)
                .hasMessageContaining(zoneId)
                .hasMessageContaining("BUILDING");
    }

    @Test
    void shouldThrowProxyTargetUnreachableExceptionWhenPortIsNull() {
        String zoneId = "zonenoport1234";
        ZoneDTO dto = ZoneDTO.builder()
                .zoneId(zoneId)
                .status(ZoneStatus.ACTIVE)
                .osrmPort(null)
                .build();
        Mockito.when(zoneService.findZone(zoneId)).thenReturn(dto);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/" + zoneId + "/osrm/route/v1/driving/0,0;1,1");

        Assertions.assertThatThrownBy(() -> target.forwardToZone(zoneId, ProxyType.OSRM, request, "route/v1/driving/0,0;1,1", ""))
                .isInstanceOf(ProxyTargetUnreachableException.class)
                .hasMessageContaining("OSRM target not configured");
    }

    private ZoneDTO activeDto(String zoneId, int osrmPort, int vroomPort) {
        return ZoneDTO.builder()
                .zoneId(zoneId)
                .status(ZoneStatus.ACTIVE)
                .osrmPort(osrmPort)
                .vroomPort(vroomPort)
                .build();
    }
}
