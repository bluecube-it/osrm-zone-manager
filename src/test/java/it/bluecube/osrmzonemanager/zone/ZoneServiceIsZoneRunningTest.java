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

class ZoneServiceIsZoneRunningTest extends BaseUnitTest {

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
    void shouldDelegateToProcessSupervisorService() {
        Mockito.when(processSupervisor.isZoneRunning("zone123")).thenReturn(true);
        Mockito.when(processSupervisor.isZoneRunning("zone456")).thenReturn(false);

        Assertions.assertThat(zoneService.isZoneRunning("zone123")).isTrue();
        Assertions.assertThat(zoneService.isZoneRunning("zone456")).isFalse();
        Mockito.verify(processSupervisor, Mockito.times(2)).isZoneRunning(Mockito.anyString());
    }
}
