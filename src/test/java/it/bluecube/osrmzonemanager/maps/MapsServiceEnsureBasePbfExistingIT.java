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

import org.assertj.core.api.Assertions;

class MapsServiceEnsureBasePbfExistingIT extends BaseIT {

    @Autowired
    private MapsService mapsService;

    @Autowired
    private OsrmZoneManagerConfig config;

    @MockitoBean
    private BootRecoveryService bootRecoveryService;

    @BeforeEach
    void setUp() {
        try {
            Path pbfPath = Path.of(config.getBasePbf());
            Files.createDirectories(pbfPath.getParent());
            Files.write(pbfPath, new byte[20]);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Test
    void shouldReturnExistingPathWhenValidPbfExists() throws Exception {
        String result = mapsService.ensureBasePbf();

        Assertions.assertThat(result).endsWith("italy.osm.pbf");
        Assertions.assertThat(Path.of(result)).exists();
    }
}
