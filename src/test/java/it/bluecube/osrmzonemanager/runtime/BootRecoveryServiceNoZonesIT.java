package it.bluecube.osrmzonemanager.runtime;

import it.bluecube.osrmzonemanager.builder.BuildPipelineService;
import it.bluecube.osrmzonemanager.maps.MapsService;
import it.bluecube.osrmzonemanager.runtime.ProcessSupervisorService;
import it.bluecube.test.integration_test.BaseIT;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

class BootRecoveryServiceNoZonesIT extends BaseIT {

    @Autowired
    private BootRecoveryService bootRecoveryService;

    @MockitoBean
    private BuildPipelineService buildPipelineService;

    @MockitoBean
    private ProcessSupervisorService processSupervisorService;

    @MockitoBean
    private MapsService mapsService;

    @Test
    void shouldReturnEarlyWhenNoZonesInRegistry() throws Exception {
        Mockito.doReturn("/tmp/base.pbf").when(mapsService).ensureBasePbf();
        Mockito.clearInvocations(mapsService);

        ReflectionTestUtils.invokeMethod(bootRecoveryService, "recover");

        Mockito.verify(mapsService).ensureBasePbf();
        Mockito.verifyNoInteractions(buildPipelineService);
        Mockito.verifyNoInteractions(processSupervisorService);
    }

    @Test
    void shouldReturnEarlyWhenBasePbfCheckFails() throws Exception {
        Mockito.doThrow(new IllegalStateException("pbf boom")).when(mapsService).ensureBasePbf();
        Mockito.clearInvocations(mapsService);

        ReflectionTestUtils.invokeMethod(bootRecoveryService, "recover");

        Mockito.verify(mapsService).ensureBasePbf();
        Mockito.verify(buildPipelineService, Mockito.never()).buildZone(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.verify(processSupervisorService, Mockito.never()).startZone(ArgumentMatchers.anyString());
    }
}
