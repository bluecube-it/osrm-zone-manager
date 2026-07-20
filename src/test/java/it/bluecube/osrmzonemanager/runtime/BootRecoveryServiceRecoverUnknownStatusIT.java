package it.bluecube.osrmzonemanager.runtime;

import it.bluecube.osrmzonemanager.builder.BuildPipelineService;
import it.bluecube.osrmzonemanager.maps.MapsService;
import it.bluecube.osrmzonemanager.zone.ZoneEntity;
import it.bluecube.osrmzonemanager.zone.ZoneRepository;
import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

class BootRecoveryServiceRecoverUnknownStatusIT extends BaseIT {

    @Autowired
    private BootRecoveryService bootRecoveryService;

    @Autowired
    private ZoneRepository zoneRepository;

    @MockitoBean
    private BuildPipelineService buildPipelineService;

    @MockitoBean
    private ProcessSupervisorService processSupervisorService;

    @MockitoBean
    private MapsService mapsService;

    @BeforeEach
    void setUp() throws Exception {
        Mockito.doReturn("/tmp/base.pbf").when(mapsService).ensureBasePbf();
    }

    @Test
    void shouldSkipZoneWithUnknownStatus() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("unknownstatus1")
                .status("WEIRD")
                .build();
        zoneRepository.save(zone);

        ReflectionTestUtils.invokeMethod(bootRecoveryService, "recover");

        Mockito.verifyNoInteractions(buildPipelineService);
        Mockito.verifyNoInteractions(processSupervisorService);
    }

    @Test
    void shouldSkipZoneWithNullStatus() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("nullstatus1234")
                .status(null)
                .build();
        zoneRepository.save(zone);

        ReflectionTestUtils.invokeMethod(bootRecoveryService, "recover");

        Mockito.verifyNoInteractions(buildPipelineService);
        Mockito.verify(processSupervisorService, Mockito.never()).startZone(ArgumentMatchers.anyString());
    }
}
