package it.bluecube.osrmzonemanager.runtime;

import it.bluecube.osrmzonemanager.builder.BuildPipelineService;
import it.bluecube.osrmzonemanager.builder.BuildResult;
import it.bluecube.osrmzonemanager.maps.MapsService;
import it.bluecube.osrmzonemanager.zone.ZoneEntity;
import it.bluecube.osrmzonemanager.zone.ZoneRepository;
import it.bluecube.osrmzonemanager.zone.ZoneStatus;
import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.assertj.core.api.Assertions;

class BootRecoveryServiceRecoverBuildingZoneIT extends BaseIT {

    @Autowired
    private BootRecoveryService bootRecoveryService;

    @Autowired
    private ZoneRepository zoneRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BuildPipelineService buildPipelineService;

    @MockitoBean
    private ProcessSupervisorService processSupervisorService;

    @MockitoBean
    private MapsService mapsService;

    @MockitoBean(name = "zoneManagerTaskExecutor")
    private Executor zoneManagerTaskExecutor;

    @BeforeEach
    void setUp() throws Exception {
        Mockito.doReturn("/tmp/base.pbf").when(mapsService).ensureBasePbf();
        Mockito.when(buildPipelineService.buildZone(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(new BuildResult("ignored", true, 5001, 3001, null)));
        Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(zoneManagerTaskExecutor).execute(ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void shouldTriggerRebuildForBuildingZone() throws Exception {
        JsonNode polygon = TestBuilders.samplePolygon();
        String polygonGeojson = objectMapper.writeValueAsString(polygon);
        String zoneId = "buildingzone12";
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId(zoneId)
                .status(ZoneStatus.BUILDING.name())
                .polygonGeojson(polygonGeojson)
                .lineStringsGeojson(null)
                .build();
        zoneRepository.save(zone);

        ReflectionTestUtils.invokeMethod(bootRecoveryService, "recover");

        Mockito.verify(buildPipelineService).buildZone(ArgumentMatchers.eq(zoneId),
                ArgumentMatchers.argThat(node -> node.equals(polygon)),
                ArgumentMatchers.isNull());
        Mockito.verify(processSupervisorService).startZone(zoneId);
    }
}
