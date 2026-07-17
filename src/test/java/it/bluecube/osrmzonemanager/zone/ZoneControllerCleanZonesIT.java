package it.bluecube.osrmzonemanager.zone;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

class ZoneControllerCleanZonesIT extends BaseIT {

    @MockitoBean
    private BuildPipelineService buildPipelineService;
    @MockitoBean
    private ProcessSupervisorService processSupervisorService;
    @MockitoBean
    private MapsService pbfDownloadService;

    @Autowired
    private ZoneRepository zoneRepository;
    @Autowired
    private OsrmZoneManagerConfig config;

    @BeforeEach
    void setUp() throws Exception {
        Path basePbf = Path.of(System.getProperty("java.io.tmpdir"), "osrm-test-data", "base", "italy.osm.pbf");
        Files.createDirectories(basePbf.getParent());
        if (!Files.exists(basePbf)) {
            Files.createFile(basePbf);
        }
        Files.setLastModifiedTime(basePbf, FileTime.fromMillis(Instant.now().toEpochMilli()));

        Mockito.when(pbfDownloadService.ensureBasePbf()).thenReturn(basePbf.toString());
        Mockito.when(buildPipelineService.buildZone(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(new BuildResult("ignored", true, 5001, 3001, null)));
        Mockito.when(processSupervisorService.isZoneRunning(ArgumentMatchers.anyString())).thenReturn(false);
        Mockito.doNothing().when(processSupervisorService).startZone(ArgumentMatchers.anyString());
    }

    @Test
    void shouldDeleteAllZonesAndRemoveDirectories() throws Exception {
        String firstZoneId = createZone(TestBuilders.samplePolygon(), null);
        String secondZoneId = createZone(TestBuilders.samplePolygon(), TestBuilders.sampleLinestrings());

        createZoneDir(firstZoneId);
        createZoneDir(secondZoneId);

        restTestClient.delete()
                .uri("/zones")
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();

        restTestClient.get()
                .uri("/zones")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(0);

        Assertions.assertThat(zoneRepository.findAll()).isEmpty();
        Assertions.assertThat(Files.exists(zonePath(firstZoneId))).isFalse();
        Assertions.assertThat(Files.exists(zonePath(secondZoneId))).isFalse();
    }

    private String createZone(tools.jackson.databind.JsonNode polygon, tools.jackson.databind.JsonNode lineStrings) throws Exception {
        Map<String, Object> body = lineStrings != null
                ? Map.of("polygon", polygon, "lineStrings", lineStrings)
                : Map.of("polygon", polygon);

        String response = restTestClient.post()
                .uri("/zones")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus().isCreated()
                .returnResult(String.class)
                .getResponseBody();

        return objectMapper.readTree(response).get("zoneId").asText();
    }

    private void createZoneDir(String zoneId) {
        try {
            Files.createDirectories(zonePath(zoneId));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Path zonePath(String zoneId) {
        return Path.of(config.getZonesDir(), zoneId);
    }
}
