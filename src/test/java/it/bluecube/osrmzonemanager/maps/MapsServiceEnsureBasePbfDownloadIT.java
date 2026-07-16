package it.bluecube.osrmzonemanager.maps;

import it.bluecube.osrmzonemanager.runtime.BootRecoveryService;
import it.bluecube.test.integration_test.BaseIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.assertj.core.api.Assertions;

class MapsServiceEnsureBasePbfDownloadIT extends BaseIT {

    @Autowired
    private MapsService mapsService;

    @MockitoBean
    private BootRecoveryService bootRecoveryService;

    @Test
    void shouldDownloadWhenNoPbfExists() throws Exception {
        String urlPath = "/test.pbf";
        WireMock.stubFor(WireMock.get(WireMock.urlEqualTo(urlPath))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withBody(new byte[20])));

        String result = mapsService.ensureBasePbf();

        Assertions.assertThat(result).endsWith("italy.osm.pbf");
        Assertions.assertThat(Path.of(result)).exists();
        Assertions.assertThat(Files.size(Path.of(result))).isGreaterThan(1L);

        Path tmp = tempDir.resolve("italy.osm.pbf.tmp");
        Assertions.assertThat(tmp).doesNotExist();
    }
}
