package it.bluecube.osrmzonemanager.runtime;

import it.bluecube.osrmzonemanager.builder.BuildPipelineService;
import it.bluecube.osrmzonemanager.builder.BuildResult;
import it.bluecube.osrmzonemanager.maps.MapsService;
import it.bluecube.osrmzonemanager.zone.ZoneController;
import it.bluecube.osrmzonemanager.zone.ZoneEntity;
import it.bluecube.osrmzonemanager.zone.ZoneInputDTO;
import it.bluecube.osrmzonemanager.zone.ZoneRepository;
import it.bluecube.osrmzonemanager.zone.ZoneStatus;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "classpath:application-test.properties")
class ZoneLifecycleRacesTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private ZoneRepository zoneRepository;

    @Autowired
    private ZoneController zoneController;

    @Autowired
    private EntityManager entityManager;

    @MockitoBean
    private BuildPipelineService buildPipelineService;

    @MockitoBean
    private ProcessSupervisorService processSupervisorService;

    @MockitoBean
    private MapsService pbfDownloadService;

    @MockitoSpyBean
    private PortAllocatorService portAllocatorService;

    private JsonNode samplePolygon;
    private JsonNode sampleLinestrings;
    private long baseMtime;

    @BeforeEach
    void setUp() throws Exception {
        zoneRepository.deleteAll();
        Mockito.reset(buildPipelineService, processSupervisorService, pbfDownloadService, portAllocatorService, zoneRepository);

        samplePolygon = objectMapper.readTree(new ClassPathResource("polygon.geojson").getInputStream());
        sampleLinestrings = objectMapper.readTree(new ClassPathResource("lineStrings.geojson").getInputStream());

        Path basePbf = Path.of(System.getProperty("java.io.tmpdir"), "osrm-test-data", "base", "italy.osm.pbf");
        Files.createDirectories(basePbf.getParent());
        if (!Files.exists(basePbf)) {
            Files.createFile(basePbf);
        }
        baseMtime = Instant.now().toEpochMilli();
        Files.setLastModifiedTime(basePbf, FileTime.fromMillis(baseMtime));

        Mockito.when(pbfDownloadService.ensureBasePbf()).thenReturn(basePbf.toString());
        Mockito.when(buildPipelineService.buildZone(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(new CompletableFuture<>());
        Mockito.when(processSupervisorService.isZoneRunning(ArgumentMatchers.anyString())).thenReturn(false);
    }

    @Test
    void testCreateZoneRegistersBuildingBeforeReturning() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "polygon", samplePolygon
                        ))))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andReturn();

        String zoneId = objectMapper.readTree(result.getResponse().getContentAsString()).get("zoneId").asText();
        ZoneEntity zone = zoneRepository.findById(zoneId).orElseThrow();
        Assertions.assertThat(zone.getStatus()).isEqualTo(ZoneStatus.BUILDING.name());
        Assertions.assertThat(zone.getPolygonGeojson()).isNotNull();
        Assertions.assertThat(objectMapper.readTree(zone.getPolygonGeojson())).isEqualTo(samplePolygon);
    }

    @Test
    void testDeleteCancelsInFlightBuild() throws Exception {
        CompletableFuture<BuildResult> buildFuture = new CompletableFuture<>();
        Mockito.when(buildPipelineService.buildZone(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(buildFuture);

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("polygon", samplePolygon))))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andReturn();
        String zoneId = objectMapper.readTree(result.getResponse().getContentAsString()).get("zoneId").asText();

        mockMvc.perform(MockMvcRequestBuilders.delete("/zones/{zoneId}", zoneId))
                .andExpect(MockMvcResultMatchers.status().isOk());

        Assertions.assertThat(buildFuture).isCancelled();
        Assertions.assertThat(zoneRepository.existsById(zoneId)).isFalse();
    }

    @Test
    void testReuseChecksProcessHealth() throws Exception {
        String zoneId = zoneId(samplePolygon, null);
        ZoneEntity zone = ZoneEntity.builder()
                .zoneId(zoneId)
                .polygonHash(sha256(objectMapper.writeValueAsBytes(samplePolygon)))
                .lineStringsHash("")
                .basePbfMtime(String.valueOf(baseMtime))
                .status(ZoneStatus.ACTIVE.name())
                .osrmPort(11111)
                .vroomPort(22222)
                .createdAt(Instant.now())
                .lastAccess(Instant.now())
                .polygonGeojson(objectMapper.writeValueAsString(samplePolygon))
                .build();
        createMapFile(zoneId);
        zoneRepository.save(zone);

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("polygon", samplePolygon))))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andReturn();

        String response = result.getResponse().getContentAsString();
        Assertions.assertThat(response).doesNotContainIgnoringCase("reusing");
        Assertions.assertThat(objectMapper.readTree(response).get("status").asText()).isEqualTo(ZoneStatus.BUILDING.name());

        ZoneEntity updated = zoneRepository.findById(zoneId).orElseThrow();
        Assertions.assertThat(updated.getStatus()).isEqualTo(ZoneStatus.BUILDING.name());
        Assertions.assertThat(updated.getOsrmPort()).isNotEqualTo(11111);
        Assertions.assertThat(updated.getVroomPort()).isNotEqualTo(22222);
    }

    @Test
    void testRegisterFailureReleasesPorts() {
        Mockito.doThrow(new RuntimeException("register boom")).when(zoneRepository).save(ArgumentMatchers.any());

        Assertions.assertThatThrownBy(() ->
                        zoneController.createZone(new ZoneInputDTO(samplePolygon, null)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("register boom");
    }

    @Test
    void testRebuildReleasesStalePorts() throws Exception {
        String zoneId = zoneId(samplePolygon, null);
        ZoneEntity zone = ZoneEntity.builder()
                .zoneId(zoneId)
                .polygonHash(sha256(objectMapper.writeValueAsBytes(samplePolygon)))
                .lineStringsHash("")
                .basePbfMtime(String.valueOf(baseMtime))
                .status(ZoneStatus.ACTIVE.name())
                .osrmPort(11111)
                .vroomPort(22222)
                .createdAt(Instant.now())
                .lastAccess(Instant.now())
                .polygonGeojson(objectMapper.writeValueAsString(samplePolygon))
                .build();
        createMapFile(zoneId);
        zoneRepository.save(zone);

        mockMvc.perform(MockMvcRequestBuilders.post("/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("polygon", samplePolygon))))
                .andExpect(MockMvcResultMatchers.status().isCreated());

        Mockito.verify(portAllocatorService).releasePort("osrm", 11111);
        Mockito.verify(portAllocatorService).releasePort("vroom", 22222);
    }

    @Test
    void testReleasePortFailureDoesNotMaskRegisterError() {
        Mockito.doThrow(new RuntimeException("register boom")).when(zoneRepository).save(ArgumentMatchers.any());
        Mockito.doThrow(new RuntimeException("release boom")).when(portAllocatorService).releasePort(ArgumentMatchers.anyString(), ArgumentMatchers.anyInt());

        Assertions.assertThatThrownBy(() ->
                        zoneController.createZone(new ZoneInputDTO(samplePolygon, null)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("register boom");
    }

    @Test
    void testDeleteCancelsInFlightStartTask() throws Exception {
        Mockito.when(buildPipelineService.buildZone(ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(
                CompletableFuture.completedFuture(new BuildResult("ignored", true, 5001, 3001, null)));

        Mockito.doAnswer(invocation -> {
            Thread.sleep(10_000);
            return null;
        }).when(processSupervisorService).startZone(ArgumentMatchers.anyString());

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("polygon", samplePolygon))))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andReturn();
        String zoneId = objectMapper.readTree(result.getResponse().getContentAsString()).get("zoneId").asText();

        Thread.sleep(500);

        mockMvc.perform(MockMvcRequestBuilders.delete("/zones/{zoneId}", zoneId))
                .andExpect(MockMvcResultMatchers.status().isOk());

        Mockito.verify(processSupervisorService).stopZone(zoneId);
    }

    @Test
    void testRegistryStoresPolygonGeojson() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("polygon", samplePolygon))))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andReturn();

        String zoneId = objectMapper.readTree(result.getResponse().getContentAsString()).get("zoneId").asText();
        ZoneEntity zone = zoneRepository.findById(zoneId).orElseThrow();
        Assertions.assertThat(objectMapper.readTree(zone.getPolygonGeojson())).isEqualTo(samplePolygon);
    }

    @Test
    void testRegistryStoresLinestringsGeojson() throws Exception {
        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/zones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "polygon", samplePolygon,
                                "lineStrings", sampleLinestrings
                        ))))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andReturn();

        String zoneId = objectMapper.readTree(result.getResponse().getContentAsString()).get("zoneId").asText();
        ZoneEntity zone = zoneRepository.findById(zoneId).orElseThrow();
        Assertions.assertThat(zone.getLineStringsGeojson()).isNotNull();
        Assertions.assertThat(objectMapper.readTree(zone.getLineStringsGeojson())).isEqualTo(sampleLinestrings);
    }

    @Test
    void testRegistryPersistsAcrossCacheReset() {
        ZoneEntity zone = ZoneEntity.builder()
                .zoneId("persistzone")
                .polygonHash("hash")
                .lineStringsHash("")
                .basePbfMtime("12345")
                .status(ZoneStatus.BUILDING.name())
                .osrmPort(5003)
                .vroomPort(3003)
                .createdAt(Instant.now())
                .lastAccess(Instant.now())
                .polygonGeojson(objectMapper.writeValueAsString(samplePolygon))
                .build();
        zoneRepository.save(zone);

        entityManager.clear();

        ZoneEntity loaded = zoneRepository.findById("persistzone").orElseThrow();
        Assertions.assertThat(objectMapper.readTree(loaded.getPolygonGeojson())).isEqualTo(samplePolygon);
    }

    private void createMapFile(String zoneId) throws Exception {
        Path mapFile = Path.of(System.getProperty("java.io.tmpdir"), "osrm-test-data", "zones", zoneId, "map.osrm.properties");
        Files.createDirectories(mapFile.getParent());
        if (!Files.exists(mapFile)) {
            Files.createFile(mapFile);
        }
    }

    private String zoneId(JsonNode polygon, JsonNode lineStrings) throws Exception {
        byte[] polygonBytes = objectMapper.writeValueAsBytes(polygon);
        byte[] source;
        if (lineStrings != null && !lineStrings.isNull()) {
            byte[] lineStringsBytes = objectMapper.writeValueAsBytes(lineStrings);
            source = new byte[polygonBytes.length + 1 + lineStringsBytes.length];
            System.arraycopy(polygonBytes, 0, source, 0, polygonBytes.length);
            source[polygonBytes.length] = (byte) '|';
            System.arraycopy(lineStringsBytes, 0, source, polygonBytes.length + 1, lineStringsBytes.length);
        } else {
            source = polygonBytes;
        }
        return sha256(source).substring(0, 12);
    }

    private String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
