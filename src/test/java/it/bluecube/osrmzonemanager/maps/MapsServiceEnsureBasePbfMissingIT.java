package it.bluecube.osrmzonemanager.maps;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.runtime.BootRecoveryService;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;

class MapsServiceEnsureBasePbfMissingIT extends BaseIT {

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
    void shouldThrowWhenPbfMissing() {
        Assertions.assertThatThrownBy(() -> mapsService.ensureBasePbf())
                .isInstanceOf(MissingBasePbfException.class)
                .hasMessageContaining("Base PBF not found");
    }
}
