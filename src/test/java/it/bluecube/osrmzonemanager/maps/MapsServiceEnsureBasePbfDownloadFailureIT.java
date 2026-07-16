package it.bluecube.osrmzonemanager.maps;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.runtime.BootRecoveryService;
import it.bluecube.test.integration_test.BaseIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.assertj.core.api.Assertions;

class MapsServiceEnsureBasePbfDownloadFailureIT extends BaseIT {

    @Autowired
    private MapsService mapsService;

    @Autowired
    private OsrmZoneManagerConfig config;

    @MockitoBean
    private BootRecoveryService bootRecoveryService;

    @BeforeEach
    void cleanPbf() throws Exception {
        Path pbfPath = Path.of(config.getBasePbf());
        Files.deleteIfExists(pbfPath);
        Files.deleteIfExists(Path.of(pbfPath + ".tmp"));
    }

    @Test
    void shouldThrowWhenDownloadFailsAndCleanTmpFile() throws Exception {
        String urlPath = "/test.pbf";
        WireMock.stubFor(WireMock.get(WireMock.urlEqualTo(urlPath))
                .willReturn(WireMock.aResponse().withStatus(500).withBody(new byte[0])));

        Assertions.assertThatThrownBy(() -> mapsService.ensureBasePbf())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("download failed");

        Path tmp = Path.of(config.getBasePbf() + ".tmp");
        Assertions.assertThat(tmp).doesNotExist();
    }
}
