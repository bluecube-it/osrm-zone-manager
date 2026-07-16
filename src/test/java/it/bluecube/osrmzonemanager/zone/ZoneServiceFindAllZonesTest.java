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

import java.time.Instant;
import java.util.List;

class ZoneServiceFindAllZonesTest extends BaseUnitTest {

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
    void shouldReturnEmptyWhenNoZones() {
        Mockito.when(zoneStateService.findAll()).thenReturn(List.of());

        List<ZoneDTO> result = zoneService.findAllZones();

        Assertions.assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnSortedByLastAccessDescNullsLast() {
        Instant now = Instant.now();
        ZoneEntity a = ZoneEntity.builder().zoneId("a").lastAccess(now.minusSeconds(60)).status(ZoneStatus.ACTIVE.name()).build();
        ZoneEntity b = ZoneEntity.builder().zoneId("b").lastAccess(now).status(ZoneStatus.BUILDING.name()).build();
        ZoneEntity c = ZoneEntity.builder().zoneId("c").lastAccess(null).status(ZoneStatus.FAILED.name()).build();

        Mockito.when(zoneStateService.findAll()).thenReturn(List.of(c, a, b));
        Mockito.when(zoneMapper.toDTO(Mockito.any())).thenAnswer(inv -> {
            ZoneEntity z = inv.getArgument(0);
            return ZoneDTO.builder().zoneId(z.getZoneId()).build();
        });

        List<ZoneDTO> result = zoneService.findAllZones();

        Assertions.assertThat(result).extracting(ZoneDTO::zoneId).containsExactly("b", "a", "c");
    }
}
