package it.bluecube.osrmzonemanager.maps;

import it.bluecube.osrmzonemanager.runtime.BootRecoveryService;
import it.bluecube.test.integration_test.BaseIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Path;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.assertj.core.api.Assertions;

class MapsServiceEnsureBasePbfDownloadFailureIT extends BaseIT {

    @Autowired
    private MapsService mapsService;

    @MockitoBean
    private BootRecoveryService bootRecoveryService;

    @Test
    void shouldThrowWhenDownloadFailsAndCleanTmpFile() throws Exception {
        String urlPath = "/test.pbf";
        WireMock.stubFor(WireMock.get(WireMock.urlEqualTo(urlPath))
                .willReturn(WireMock.aResponse().withStatus(500)));

        Assertions.assertThatThrownBy(() -> mapsService.ensureBasePbf())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("download failed");

        Path tmp = tempDir.resolve("italy.osm.pbf.tmp");
        Assertions.assertThat(tmp).doesNotExist();
    }
}
