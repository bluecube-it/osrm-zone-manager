package it.bluecube.osrmzonemanager.runtime;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.builder.BuildPipelineService;
import it.bluecube.osrmzonemanager.builder.BuildResult;
import it.bluecube.osrmzonemanager.maps.MapsService;
import it.bluecube.osrmzonemanager.zone.ZoneEntity;
import it.bluecube.osrmzonemanager.zone.ZoneFiles;
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
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

class BootRecoveryServiceRecoverBuiltZoneIT extends BaseIT {

    @Autowired
    private BootRecoveryService bootRecoveryService;

    @Autowired
    private ZoneRepository zoneRepository;

    @Autowired
    private OsrmZoneManagerConfig config;

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
    void shouldStartBuiltZoneWhenMapFileExists() throws Exception {
        String zoneId = "builtmapexists1";
        String polygonGeojson = objectMapper.writeValueAsString(TestBuilders.samplePolygon());
        createZoneFiles(zoneId, polygonGeojson);

        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId(zoneId)
                .status(ZoneStatus.BUILT.name())
                .polygonGeojson(polygonGeojson)
                .build();
        zoneRepository.save(zone);

        ReflectionTestUtils.invokeMethod(bootRecoveryService, "recover");

        Mockito.verify(processSupervisorService).startZone(zoneId);
        Mockito.verify(buildPipelineService, Mockito.never()).buildZone(ArgumentMatchers.eq(zoneId),
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void shouldRebuildBuiltZoneWhenMapFileMissing() throws Exception {
        String zoneId = "builtmissingmap1";
        String polygonGeojson = objectMapper.writeValueAsString(TestBuilders.samplePolygon());

        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId(zoneId)
                .status(ZoneStatus.BUILT.name())
                .polygonGeojson(polygonGeojson)
                .build();
        zoneRepository.save(zone);

        ReflectionTestUtils.invokeMethod(bootRecoveryService, "recover");

        Mockito.verify(buildPipelineService).buildZone(ArgumentMatchers.eq(zoneId),
                ArgumentMatchers.any(), ArgumentMatchers.isNull());
        Mockito.verify(processSupervisorService).startZone(zoneId);
    }

    @Test
    void shouldStartStartingZoneWhenMapFileExists() throws Exception {
        String zoneId = "startingmapexists";
        String polygonGeojson = objectMapper.writeValueAsString(TestBuilders.samplePolygon());
        createZoneFiles(zoneId, polygonGeojson);

        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId(zoneId)
                .status(ZoneStatus.STARTING.name())
                .polygonGeojson(polygonGeojson)
                .build();
        zoneRepository.save(zone);

        ReflectionTestUtils.invokeMethod(bootRecoveryService, "recover");

        Mockito.verify(processSupervisorService).startZone(zoneId);
        Mockito.verify(buildPipelineService, Mockito.never()).buildZone(ArgumentMatchers.eq(zoneId),
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    private void createZoneFiles(String zoneId, String polygonGeojson) throws Exception {
        Path zoneDir = Path.of(config.getZonesDir(), zoneId);
        Files.createDirectories(zoneDir);
        Files.writeString(zoneDir.resolve(ZoneFiles.POLYGON_GEOJSON), polygonGeojson);
        Files.writeString(zoneDir.resolve(ZoneFiles.MAP_OSRM_PROPERTIES), "");
    }
}
