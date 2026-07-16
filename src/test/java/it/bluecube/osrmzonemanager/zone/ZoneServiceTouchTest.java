package it.bluecube.osrmzonemanager.zone;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.builder.BuildPipelineService;
import it.bluecube.osrmzonemanager.maps.MapsService;
import it.bluecube.osrmzonemanager.runtime.PortAllocatorService;
import it.bluecube.osrmzonemanager.runtime.ProcessSupervisorService;
import it.bluecube.test.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

class ZoneServiceTouchTest extends BaseUnitTest {

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
    void shouldDelegateToZoneStateServiceTouch() {
        zoneService.touch("zone123");
        Mockito.verify(zoneStateService).touch("zone123");
    }
}
