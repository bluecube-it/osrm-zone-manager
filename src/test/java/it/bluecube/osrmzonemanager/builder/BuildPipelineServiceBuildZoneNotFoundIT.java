package it.bluecube.osrmzonemanager.builder;

import it.bluecube.osrmzonemanager.maps.MapsService;
import it.bluecube.osrmzonemanager.runtime.ProcessSupervisorService;
import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;

import java.util.concurrent.Semaphore;

import org.assertj.core.api.Assertions;

class BuildPipelineServiceBuildZoneNotFoundIT extends BaseIT {

    @Autowired
    private BuildPipelineService buildPipelineService;

    @MockitoSpyBean
    private BuildPipelineService spyBuildPipelineService;

    @MockitoBean
    private ProcessSupervisorService processSupervisorService;

    @MockitoBean
    private MapsService mapsService;

    @Test
    void shouldReturnFailedResultWhenZoneNotInRegistry() throws Exception {
        JsonNode polygon = TestBuilders.samplePolygon();

        BuildResult result = spyBuildPipelineService.buildZone("missing-zone", polygon, null).get();

        Assertions.assertThat(result.ok()).isFalse();
        Assertions.assertThat(result.error()).contains("not found in registry");
        Assertions.assertThat(result.osrmPort()).isNull();
        Assertions.assertThat(result.vroomPort()).isNull();

        Mockito.verify(spyBuildPipelineService, Mockito.never()).runSubprocess(Mockito.any(), Mockito.any());
        Mockito.verify(processSupervisorService, Mockito.never()).startZone(Mockito.any());

        Semaphore semaphore = (Semaphore) ReflectionTestUtils.getField(buildPipelineService, "buildSlots");
        Assertions.assertThat(semaphore.availablePermits()).isEqualTo(3);
    }
}
