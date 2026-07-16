package it.bluecube.osrmzonemanager.zone;

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

class ZoneControllerDeleteZoneIT extends BaseIT {

    @MockitoBean
    private BuildPipelineService buildPipelineService;
    @MockitoBean
    private ProcessSupervisorService processSupervisorService;
    @MockitoBean
    private MapsService pbfDownloadService;

    @Autowired
    private ZoneRepository zoneRepository;

    private final String zoneId = "zonedel123456";

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
                .thenReturn(new CompletableFuture<>());
        Mockito.when(processSupervisorService.isZoneRunning(ArgumentMatchers.anyString())).thenReturn(false);
    }

    @Test
    void shouldDeleteExistingZone() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId(zoneId)
                .status(ZoneStatus.FAILED.name())
                .build();
        zoneRepository.save(zone);

        restTestClient.delete()
                .uri("/zones/{zoneId}", zoneId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.zoneId").isEqualTo(zoneId)
                .jsonPath("$.message").isEqualTo("deleted");

        Assertions.assertThat(zoneRepository.existsById(zoneId)).isFalse();
    }

    @Test
    void shouldReturnNotFoundForNonExistentZone() {
        restTestClient.delete()
                .uri("/zones/{zoneId}", "nonexistent123")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").exists();
    }

    @Test
    void shouldStopProcessesAndRemoveDirectoryForActiveZone() throws Exception {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId(zoneId)
                .status(ZoneStatus.ACTIVE.name())
                .build();
        zoneRepository.save(zone);
        createZoneDir(zoneId);
        Mockito.when(processSupervisorService.isZoneRunning(zoneId)).thenReturn(true);

        restTestClient.delete()
                .uri("/zones/{zoneId}", zoneId)
                .exchange()
                .expectStatus().isOk();

        Mockito.verify(processSupervisorService).stopZone(zoneId);
        Assertions.assertThat(zoneRepository.existsById(zoneId)).isFalse();
        Assertions.assertThat(Files.exists(Path.of(System.getProperty("java.io.tmpdir"), "osrm-test-data", "zones", zoneId))).isFalse();
    }

    @Test
    void shouldCancelBuildFutureForBuildingZone() throws Exception {
        CompletableFuture<BuildResult> buildFuture = new CompletableFuture<>();
        Mockito.when(buildPipelineService.buildZone(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(buildFuture);

        var createResponse = restTestClient.post()
                .uri("/zones")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("polygon", TestBuilders.samplePolygon()))
                .exchange();

        String createdZoneId = createResponse.returnResult(String.class)
                .getResponseBody();
        createdZoneId = objectMapper.readTree(createdZoneId).get("zoneId").asText();

        restTestClient.delete()
                .uri("/zones/{zoneId}", createdZoneId)
                .exchange()
                .expectStatus().isOk();

        Assertions.assertThat(buildFuture).isCancelled();
        Assertions.assertThat(zoneRepository.existsById(createdZoneId)).isFalse();
    }

    private void createZoneDir(String zoneId) throws Exception {
        Path zoneDir = Path.of(System.getProperty("java.io.tmpdir"), "osrm-test-data", "zones", zoneId);
        Files.createDirectories(zoneDir);
        Path file = zoneDir.resolve("map.osrm.properties");
        if (!Files.exists(file)) {
            Files.createFile(file);
        }
    }
}
