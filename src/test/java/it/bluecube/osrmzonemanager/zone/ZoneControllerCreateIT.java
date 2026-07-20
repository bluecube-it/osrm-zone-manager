package it.bluecube.osrmzonemanager.zone;

import it.bluecube.osrmzonemanager.HashUtils;
import it.bluecube.osrmzonemanager.builder.BuildPipelineService;
import it.bluecube.osrmzonemanager.builder.BuildResult;
import it.bluecube.osrmzonemanager.maps.MapsService;
import it.bluecube.osrmzonemanager.runtime.ProcessSupervisorService;
import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

class ZoneControllerCreateIT extends BaseIT {

    @MockitoBean
    private BuildPipelineService buildPipelineService;
    @MockitoBean
    private ProcessSupervisorService processSupervisorService;
    @MockitoBean
    private MapsService pbfDownloadService;

    @Autowired
    private ZoneRepository zoneRepository;

    private JsonNode samplePolygon;
    private JsonNode sampleLinestrings;
    private long baseMtime;

    @BeforeEach
    void setUp() throws Exception {
        samplePolygon = TestBuilders.samplePolygon();
        sampleLinestrings = TestBuilders.sampleLinestrings();

        Path basePbf = Path.of(System.getProperty("java.io.tmpdir"), "osrm-test-data", "base", "italy.osm.pbf");
        Files.createDirectories(basePbf.getParent());
        if (!Files.exists(basePbf)) {
            Files.createFile(basePbf);
        }
        baseMtime = Instant.now().toEpochMilli();
        Files.setLastModifiedTime(basePbf, FileTime.fromMillis(baseMtime));

        Mockito.when(pbfDownloadService.ensureBasePbf()).thenReturn(basePbf.toString());
        Mockito.when(buildPipelineService.buildZone(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(new BuildResult("ignored", true, 5001, 3001, null)));
        Mockito.when(processSupervisorService.isZoneRunning(ArgumentMatchers.anyString())).thenReturn(false);
        Mockito.doNothing().when(processSupervisorService).startZone(ArgumentMatchers.anyString());
    }

    @Test
    void shouldCreateNewZone() {
        var response = restTestClient.post()
                .uri("/zones")
                .body(Map.of("polygon", samplePolygon))
                .exchange();

        response.expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.zoneId").exists()
                .jsonPath("$.status").isEqualTo(ZoneStatus.BUILDING.name())
                .jsonPath("$.message").exists();
    }

    @Test
    void shouldReuseExistingZoneWhenRunningAndSameContent() throws Exception {
        ZoneEntity existing = buildExistingZone(ZoneStatus.ACTIVE.name());
        createMapFile(existing.getZoneId());
        zoneRepository.save(existing);
        Mockito.when(processSupervisorService.isZoneRunning(existing.getZoneId())).thenReturn(true);

        var response = restTestClient.post()
                .uri("/zones")
                .body(Map.of("polygon", samplePolygon))
                .exchange();

        response.expectStatus().isOk()
                .expectBody()
                .jsonPath("$.zoneId").isEqualTo(existing.getZoneId())
                .jsonPath("$.status").isEqualTo(ZoneStatus.ACTIVE.name())
                .jsonPath("$.message").isEqualTo("zone already active with same content — reusing");
    }

    @Test
    void shouldReturnConflictWhenZoneInProgressWithSameContent() throws Exception {
        ZoneEntity existing = buildExistingZone(ZoneStatus.BUILDING.name());
        zoneRepository.save(existing);

        var response = restTestClient.post()
                .uri("/zones")
                .body(Map.of("polygon", samplePolygon))
                .exchange();

        response.expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.error").value(error -> Assertions.assertThat(error.toString()).contains("is BUILDING"));
    }

    @Test
    void shouldCreateZoneWithLinestrings() {
        var response = restTestClient.post()
                .uri("/zones")
                .body(Map.of("polygon", samplePolygon, "lineStrings", sampleLinestrings))
                .exchange();

        response.expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.zoneId").exists()
                .jsonPath("$.status").isEqualTo(ZoneStatus.BUILDING.name());
    }

    private ZoneEntity buildExistingZone(String status) throws Exception {
        String polygonHash = HashUtils.sha256(objectMapper.writeValueAsBytes(samplePolygon));
        String lineStringsHash = "";
        String zoneId = HashUtils.sha256(objectMapper.writeValueAsBytes(samplePolygon)).substring(0, 12);

        return ZoneEntity.builder()
                .zoneId(zoneId)
                .polygonHash(polygonHash)
                .lineStringsHash(lineStringsHash)
                .basePbfMtime(String.valueOf(baseMtime))
                .status(status)
                .osrmPort(11111)
                .vroomPort(22222)
                .createdAt(Instant.now())
                .lastAccess(Instant.now())
                .polygonGeojson(objectMapper.writeValueAsString(samplePolygon))
                .build();
    }

    private void createMapFile(String zoneId) throws Exception {
        Path mapFile = Path.of(System.getProperty("java.io.tmpdir"), "osrm-test-data", "zones", zoneId, "map.osrm.properties");
        Files.createDirectories(mapFile.getParent());
        Files.writeString(mapFile, "");
    }
}
