package it.bluecube.osrmzonemanager.zone;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.builder.BuildPipelineService;
import it.bluecube.osrmzonemanager.builder.BuildResult;
import it.bluecube.osrmzonemanager.maps.MapsService;
import it.bluecube.osrmzonemanager.runtime.PortAllocatorService;
import it.bluecube.osrmzonemanager.runtime.ProcessSupervisorService;
import it.bluecube.test.BaseUnitTest;
import it.bluecube.test.TestBuilders;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

class ZoneServiceDeleteZoneTest extends BaseUnitTest {

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
    @Mock
    private Executor zoneManagerTaskExecutor;
    @InjectMocks
    private ZoneService zoneService;

    private Path zonesDir;

    @BeforeEach
    void setUp() throws Exception {
        zonesDir = Files.createTempDirectory("zones");
        Mockito.when(config.getZonesDir()).thenReturn(zonesDir.toString());
    }

    @Test
    void shouldStopProcessesForRunningOrStartingStatus() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("zone12345678")
                .status(ZoneStatus.ACTIVE.name())
                .build();
        Mockito.when(zoneStateService.findById("zone12345678")).thenReturn(Optional.of(zone));

        zoneService.deleteZone("zone12345678");

        Mockito.verify(processSupervisor).stopZone("zone12345678");
        Mockito.verify(zoneStateService).deleteById("zone12345678");
    }

    @Test
    void shouldCancelBuildFutureForBuildingStatus() {
        String zoneId = "zone12345678";
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId(zoneId)
                .status(ZoneStatus.BUILDING.name())
                .build();
        Mockito.when(zoneStateService.findById(zoneId)).thenReturn(Optional.of(zone));

        CompletableFuture<BuildResult> buildFuture = new CompletableFuture<>();
        ConcurrentHashMap<String, CompletableFuture<BuildResult>> buildTasks = new ConcurrentHashMap<>();
        buildTasks.put(zoneId, buildFuture);
        ReflectionTestUtils.setField(zoneService, "buildTasks", buildTasks);

        zoneService.deleteZone(zoneId);

        Assertions.assertThat(buildFuture).isCancelled();
        Mockito.verify(zoneStateService).deleteById(zoneId);
    }

    @Test
    void shouldRemoveZoneDirectory() throws Exception {
        String zoneId = "zone12345678";
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId(zoneId)
                .status(ZoneStatus.FAILED.name())
                .build();
        Mockito.when(zoneStateService.findById(zoneId)).thenReturn(Optional.of(zone));

        Path zoneDir = zonesDir.resolve(zoneId);
        Files.createDirectories(zoneDir);
        Files.createFile(zoneDir.resolve("file.txt"));

        zoneService.deleteZone(zoneId);

        Assertions.assertThat(Files.exists(zoneDir)).isFalse();
    }

    @Test
    void shouldDeleteRecordFromDb() {
        String zoneId = "zone12345678";
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId(zoneId)
                .status(ZoneStatus.FAILED.name())
                .build();
        Mockito.when(zoneStateService.findById(zoneId)).thenReturn(Optional.of(zone));

        zoneService.deleteZone(zoneId);

        Mockito.verify(zoneStateService).deleteById(zoneId);
    }
}
