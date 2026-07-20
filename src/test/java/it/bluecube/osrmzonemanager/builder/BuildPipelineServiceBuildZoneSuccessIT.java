package it.bluecube.osrmzonemanager.builder;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.maps.MapsService;
import it.bluecube.osrmzonemanager.runtime.PortAllocatorService;
import it.bluecube.osrmzonemanager.runtime.ProcessSupervisorService;
import it.bluecube.osrmzonemanager.zone.ZoneEntity;
import it.bluecube.osrmzonemanager.zone.ZoneRepository;
import it.bluecube.osrmzonemanager.zone.ZoneStateService;
import it.bluecube.osrmzonemanager.zone.ZoneStatus;
import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;

class BuildPipelineServiceBuildZoneSuccessIT extends BaseIT {

    @Autowired
    private BuildPipelineService buildPipelineService;

    @Autowired
    private ZoneStateService zoneStateService;

    @Autowired
    private ZoneRepository zoneRepository;

    @Autowired
    private OsrmZoneManagerConfig config;

    @Autowired
    private PortAllocatorService portAllocator;

    @MockitoSpyBean
    private BuildPipelineService spyBuildPipelineService;

    @MockitoBean
    private ProcessSupervisorService processSupervisorService;

    @MockitoBean
    private MapsService mapsService;

    @BeforeEach
    void setUp() throws Exception {
        Mockito.lenient().doNothing().when(processSupervisorService).startZone(ArgumentMatchers.any());
        Mockito.lenient().when(mapsService.ensureBasePbf()).thenReturn(config.getBasePbf());

        Path zonesDir = Path.of(config.getZonesDir());
        if (Files.exists(zonesDir)) {
            try (var walk = Files.walk(zonesDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (Exception ignored) {
                                // nothing to do
                            }
                        });
            }
        }
    }

    @Test
    void shouldBuildZoneAndMarkBuilt() throws Exception {
        String zoneId = "test-build-123";
        int osrmPort = 5001;
        int vroomPort = 3001;

        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId(zoneId)
                .status(ZoneStatus.BUILDING.name())
                .osrmPort(osrmPort)
                .vroomPort(vroomPort)
                .build();
        runInTransaction(() -> zoneRepository.save(zone));

        Mockito.doAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            File cwd = invocation.getArgument(1);
            Path workingDir = cwd != null ? cwd.toPath() : Path.of(config.getZonesDir(), zoneId);
            String first = command.get(0);
            String second = command.size() > 1 ? command.get(1) : "";

            if ("osmium".equals(first) && "extract".equals(second)) {
                int idx = command.indexOf("-o");
                if (idx >= 0 && idx + 1 < command.size()) {
                    Path out = Path.of(command.get(idx + 1));
                    Files.createDirectories(out.getParent());
                    if (!Files.exists(out)) {
                        Files.createFile(out);
                    }
                }
            } else if ("osmium".equals(first) && "merge".equals(second)) {
                int idx = command.indexOf("-o");
                if (idx >= 0 && idx + 1 < command.size()) {
                    Path out = Path.of(command.get(idx + 1));
                    Files.createDirectories(out.getParent());
                    if (!Files.exists(out)) {
                        Files.createFile(out);
                    }
                }
            } else if ("osrm-extract".equals(first)) {
                Path mapFile = workingDir.resolve("map.osrm.properties");
                Files.createDirectories(mapFile.getParent());
                if (!Files.exists(mapFile)) {
                    Files.createFile(mapFile);
                }
            } else if ("python3".equals(first)) {
                Path customPbf = workingDir.resolve("custom_ways.pbf");
                Files.createDirectories(customPbf.getParent());
                if (!Files.exists(customPbf)) {
                    Files.createFile(customPbf);
                }
            }
            return null;
        }).when(spyBuildPipelineService).runSubprocess(ArgumentMatchers.anyList(), ArgumentMatchers.any());

        JsonNode polygon = TestBuilders.samplePolygon();
        BuildResult result = spyBuildPipelineService.buildZone(zoneId, polygon, null).get();

        Assertions.assertThat(result.ok()).isTrue();
        Assertions.assertThat(result.zoneId()).isEqualTo(zoneId);
        Assertions.assertThat(result.osrmPort()).isEqualTo(osrmPort);
        Assertions.assertThat(result.vroomPort()).isEqualTo(vroomPort);
        Assertions.assertThat(result.error()).isNull();

        Optional<ZoneEntity> updated = runInTransaction(() -> zoneRepository.findById(zoneId));
        Assertions.assertThat(updated).isPresent();
        Assertions.assertThat(updated.get().getStatus()).isEqualTo(ZoneStatus.BUILT.name());

        Semaphore semaphore = (Semaphore) ReflectionTestUtils.getField(spyBuildPipelineService, "buildSlots");
        Assertions.assertThat(semaphore.availablePermits()).isEqualTo(3);
    }
}
