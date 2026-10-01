package it.bluecube.osrmzonemanager.runtime;

import it.bluecube.osrmzonemanager.HashUtils;
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

/**
 * Self-heal behaviour of boot recovery: a zone marked {@code FAILED} must not stay a zombie — it is
 * restarted when its map is still loadable, rebuilt when the map is stale or incomplete.
 */
class BootRecoveryServiceSelfHealIT extends BaseIT {

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

    @MockitoBean
    private OsrmMapFingerprint mapFingerprint;

    @MockitoBean(name = "zoneManagerTaskExecutor")
    private Executor zoneManagerTaskExecutor;

    @BeforeEach
    void setUp() throws Exception {
        Mockito.lenient().when(mapFingerprint.pbfFingerprint(ArgumentMatchers.any())).thenReturn("12345");
        Mockito.doReturn("/tmp/base.pbf").when(mapsService).ensureBasePbf();
        Mockito.when(buildPipelineService.buildZone(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(new BuildResult("ignored", true, 5001, null)));
        Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(zoneManagerTaskExecutor).execute(ArgumentMatchers.any(Runnable.class));
    }

    @Test
    void shouldRestartFailedZoneWhoseMapIsStillLoadable() throws Exception {
        String zoneId = "failedusable1";
        createZoneDir(zoneId);
        saveZone(zoneId, ZoneStatus.FAILED);

        Mockito.when(mapFingerprint.isUsable(ArgumentMatchers.any(), ArgumentMatchers.anyString())).thenReturn(true);
        ReflectionTestUtils.invokeMethod(bootRecoveryService, "recover");

        Mockito.verify(processSupervisorService).startZone(zoneId);
        Mockito.verify(buildPipelineService, Mockito.never()).buildZone(ArgumentMatchers.eq(zoneId),
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void shouldRebuildFailedZoneWhoseMapIsStale() throws Exception {
        String zoneId = "failedstale1";
        createZoneDir(zoneId);
        saveZone(zoneId, ZoneStatus.FAILED);

        Mockito.when(mapFingerprint.isUsable(ArgumentMatchers.any(), ArgumentMatchers.anyString())).thenReturn(false);
        ReflectionTestUtils.invokeMethod(bootRecoveryService, "recover");

        Mockito.verify(buildPipelineService).buildZone(ArgumentMatchers.eq(zoneId),
                ArgumentMatchers.any(), ArgumentMatchers.isNull());
        Mockito.verify(processSupervisorService).startZone(zoneId);
    }

    @Test
    void shouldRebuildActiveZoneWhenOsrmVersionChanged() throws Exception {
        String zoneId = "activebumped1";
        createZoneDir(zoneId);
        saveZone(zoneId, ZoneStatus.ACTIVE);

        // map complete and polygon unchanged, but produced by a different OSRM version
        Mockito.when(mapFingerprint.isUsable(ArgumentMatchers.any(), ArgumentMatchers.anyString())).thenReturn(false);
        ReflectionTestUtils.invokeMethod(bootRecoveryService, "recover");

        Mockito.verify(buildPipelineService).buildZone(ArgumentMatchers.eq(zoneId),
                ArgumentMatchers.any(), ArgumentMatchers.isNull());
    }

    private void saveZone(String zoneId, ZoneStatus status) throws Exception {
        String polygonGeojson = objectMapper.writeValueAsString(TestBuilders.samplePolygon());
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId(zoneId)
                .status(status.name())
                .polygonHash(HashUtils.sha256(polygonGeojson.getBytes()))
                .polygonGeojson(polygonGeojson)
                .build();
        zoneRepository.save(zone);
    }

    private void createZoneDir(String zoneId) throws Exception {
        Path zoneDir = Path.of(config.getZonesDir(), zoneId);
        Files.createDirectories(zoneDir);
        Files.writeString(zoneDir.resolve(ZoneFiles.POLYGON_GEOJSON),
                objectMapper.writeValueAsString(TestBuilders.samplePolygon()));
        Files.writeString(zoneDir.resolve(ZoneFiles.MAP_OSRM_PROPERTIES), "");
    }
}
