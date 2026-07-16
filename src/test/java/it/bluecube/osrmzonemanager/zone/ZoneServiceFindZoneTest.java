package it.bluecube.osrmzonemanager.zone;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.builder.BuildPipelineService;
import it.bluecube.osrmzonemanager.maps.MapsService;
import it.bluecube.osrmzonemanager.runtime.PortAllocatorService;
import it.bluecube.osrmzonemanager.runtime.ProcessSupervisorService;
import it.bluecube.test.BaseUnitTest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

class ZoneServiceFindZoneTest extends BaseUnitTest {

    @Mock
    private ZoneStateService zoneStateService;
    @Mock
    private PortAllocatorService portAllocator;
    @Mock
    private BuildPipelineService buildPipelineService;
    @Mock
    private ProcessSupervisorService processSupervisor;
    @Mock
    private MapsService pbfDownloadService;
    @Mock
    private OsrmZoneManagerConfig config;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ZoneMapper zoneMapper;
    @InjectMocks
    private ZoneService zoneService;

    @Test
    void shouldReturnZoneDtoWhenFound() {
        ZoneEntity entity = ZoneEntity.builder()
                .zoneId("found1234567")
                .status(ZoneStatus.ACTIVE.name())
                .build();
        ZoneDTO dto = ZoneDTO.builder().zoneId("found1234567").status(ZoneStatus.ACTIVE).build();
        Mockito.when(zoneStateService.findById("found1234567")).thenReturn(Optional.of(entity));
        Mockito.when(zoneMapper.toDTO(entity)).thenReturn(dto);

        ZoneDTO result = zoneService.findZone("found1234567");

        Assertions.assertThat(result.zoneId()).isEqualTo("found1234567");
        Assertions.assertThat(result.status()).isEqualTo(ZoneStatus.ACTIVE);
    }

    @Test
    void shouldThrowZoneNotFoundExceptionWhenNotFound() {
        Mockito.when(zoneStateService.findById("missing1234567")).thenReturn(Optional.empty());

        Assertions.assertThatThrownBy(() -> zoneService.findZone("missing1234567"))
                .isInstanceOf(ZoneNotFoundException.class)
                .hasMessageContaining("missing1234567");
    }
}
