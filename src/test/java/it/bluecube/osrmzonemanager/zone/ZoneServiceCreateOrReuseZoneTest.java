package it.bluecube.osrmzonemanager.zone;

import it.bluecube.osrmzonemanager.HashUtils;
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
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;


class ZoneServiceCreateOrReuseZoneTest extends BaseUnitTest {

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
    private Path basePbf;
    private String baseMtime;
    private String polygonHash;
    private String zoneId;

    @BeforeEach
    void setUp() throws Exception {
        zonesDir = Files.createTempDirectory("zones");
        basePbf = Files.createTempDirectory("pbf").resolve("base.pbf");
        Files.createFile(basePbf);
        baseMtime = String.valueOf(Files.getLastModifiedTime(basePbf).toMillis());

        polygonHash = HashUtils.sha256(TestBuilders.samplePolygon().toString().getBytes());
        zoneId = HashUtils.sha256(TestBuilders.samplePolygon().toString().getBytes()).substring(0, 12);

        Mockito.when(pbfDownloadService.ensureBasePbf()).thenReturn(basePbf.toString());
        Mockito.lenient().when(config.getZonesDir()).thenReturn(zonesDir.toString());
        Mockito.lenient().when(portAllocator.reservePortPair()).thenReturn(new int[]{5001, 3001});
        Mockito.lenient().when(buildPipelineService.buildZone(Mockito.anyString(), Mockito.any(), Mockito.any()))
                .thenReturn(CompletableFuture.completedFuture(new BuildResult("z", true, 5001, 3001, null)));
        Mockito.lenient().when(objectMapper.writeValueAsBytes(Mockito.any())).thenAnswer(inv -> inv.getArgument(0).toString().getBytes());
        Mockito.lenient().when(objectMapper.writeValueAsString(Mockito.any())).thenAnswer(inv -> inv.getArgument(0).toString());
    }

    @Test
    void shouldCreateNewZoneWhenContentDoesNotExist() {
        Mockito.when(zoneStateService.findById(Mockito.anyString())).thenReturn(Optional.empty());
        Mockito.when(zoneMapper.toZoneDTO(Mockito.any(), Mockito.anyString()))
                .thenReturn(ZoneDTO.builder().zoneId("z123").build());

        ZoneDTO result = zoneService.createOrReuseZone(TestBuilders.samplePolygon(), null);

        Assertions.assertThat(result.zoneId()).isEqualTo("z123");
        Mockito.verify(zoneStateService).markZoneBuilding(Mockito.anyString());
        Mockito.verify(buildPipelineService).buildZone(Mockito.anyString(), Mockito.any(), Mockito.any());
    }

    @Test
    void shouldReuseExistingZoneWhenHashMatchesAndRunning() throws Exception {
        ZoneEntity existing = existingMatchingZone(zoneId);

        Mockito.when(zoneStateService.findById(Mockito.anyString())).thenReturn(Optional.of(existing));
        Mockito.when(processSupervisor.isZoneRunning(zoneId)).thenReturn(true);
        Mockito.when(zoneMapper.toZoneDTO(Mockito.eq(existing), Mockito.eq("zone already active with same content — reusing")))
                .thenReturn(ZoneDTO.builder().zoneId(zoneId).message("zone already active with same content — reusing").build());

        Path mapFile = zonesDir.resolve(zoneId).resolve(ZoneFiles.MAP_OSRM_PROPERTIES);
        Files.createDirectories(mapFile.getParent());
        Files.createFile(mapFile);

        ZoneDTO result = zoneService.createOrReuseZone(TestBuilders.samplePolygon(), null);

        Assertions.assertThat(result.zoneId()).isEqualTo(zoneId);
        Assertions.assertThat(result.message()).isEqualTo("zone already active with same content — reusing");
        Mockito.verify(buildPipelineService, Mockito.never()).buildZone(Mockito.anyString(), Mockito.any(), Mockito.any());
    }

    @Test
    void shouldThrowWhenZoneInProgress() {
        ZoneEntity existing = ZoneEntity.builder()
                .zoneId(zoneId)
                .polygonHash(polygonHash)
                .lineStringsHash("")
                .basePbfMtime(baseMtime)
                .status(ZoneStatus.BUILDING.name())
                .osrmPort(11111)
                .vroomPort(22222)
                .createdAt(Instant.now())
                .lastAccess(Instant.now())
                .polygonGeojson(TestBuilders.samplePolygon().toString())
                .build();

        Mockito.when(zoneStateService.findById(Mockito.anyString())).thenReturn(Optional.of(existing));

        Assertions.assertThatThrownBy(() -> zoneService.createOrReuseZone(TestBuilders.samplePolygon(), null))
                .isInstanceOf(ZoneInProgressException.class);
    }

    @Test
    void shouldRebuildWhenProcessesNotRunning() throws Exception {
        ZoneEntity existing = existingMatchingZone(zoneId);

        Mockito.when(zoneStateService.findById(Mockito.anyString())).thenReturn(Optional.of(existing));
        Mockito.when(processSupervisor.isZoneRunning(zoneId)).thenReturn(false);
        Mockito.when(zoneMapper.toZoneDTO(Mockito.any(), Mockito.anyString()))
                .thenReturn(ZoneDTO.builder().zoneId("newid").build());

        Path mapFile = zonesDir.resolve(zoneId).resolve(ZoneFiles.MAP_OSRM_PROPERTIES);
        Files.createDirectories(mapFile.getParent());
        Files.createFile(mapFile);

        ZoneDTO result = zoneService.createOrReuseZone(TestBuilders.samplePolygon(), null);

        Assertions.assertThat(result.zoneId()).isEqualTo("newid");
        Mockito.verify(processSupervisor).stopZone(zoneId);
        Mockito.verify(zoneStateService).markZoneDegraded(zoneId, null);
        Mockito.verify(buildPipelineService).buildZone(Mockito.anyString(), Mockito.any(), Mockito.any());
    }

    private ZoneEntity existingMatchingZone(String id) {
        return ZoneEntity.builder()
                .zoneId(id)
                .polygonHash(polygonHash)
                .lineStringsHash("")
                .basePbfMtime(baseMtime)
                .status(ZoneStatus.ACTIVE.name())
                .osrmPort(11111)
                .vroomPort(22222)
                .createdAt(Instant.now())
                .lastAccess(Instant.now())
                .polygonGeojson(TestBuilders.samplePolygon().toString())
                .build();
    }
}
