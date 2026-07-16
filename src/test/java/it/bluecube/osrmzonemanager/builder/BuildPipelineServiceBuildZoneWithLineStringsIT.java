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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.assertj.core.api.Assertions;

class BuildPipelineServiceBuildZoneWithLineStringsIT extends BaseIT {

    @Autowired
    private ZoneStateService zoneStateService;

    @Autowired
    private ZoneRepository zoneRepository;

    @Autowired
    private OsrmZoneManagerConfig config;

    @MockitoSpyBean
    private BuildPipelineService spyBuildPipelineService;

    @MockitoBean
    private ProcessSupervisorService processSupervisorService;

    @MockitoBean
    private MapsService mapsService;

    @Autowired
    private PortAllocatorService portAllocator;

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
    void shouldBuildZoneWithLineStringsAndReduceScriptCalled() throws Exception {
        String zoneId = "test-build-lines-1";
        int osrmPort = 5001;
        int vroomPort = 3001;

        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId(zoneId)
                .status(ZoneStatus.BUILDING.name())
                .osrmPort(osrmPort)
                .vroomPort(vroomPort)
                .build();
        runInTransaction(() -> zoneRepository.save(zone));

        AtomicBoolean reduceCalled = new AtomicBoolean(false);
        Path zoneDir = Path.of(config.getZonesDir(), zoneId);

        Mockito.doAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            File cwd = invocation.getArgument(1);
            Path workingDir = cwd != null ? cwd.toPath() : zoneDir;
            String first = command.get(0);
            String second = command.size() > 1 ? command.get(1) : "";

            if ("python3".equals(first)) {
                reduceCalled.set(true);
                Path customPbf = workingDir.resolve("custom_ways.pbf");
                Files.createDirectories(customPbf.getParent());
                if (!Files.exists(customPbf)) {
                    Files.createFile(customPbf);
                }
            } else if ("osmium".equals(first) && "extract".equals(second)) {
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
            }
            return null;
        }).when(spyBuildPipelineService).runSubprocess(ArgumentMatchers.anyList(), ArgumentMatchers.any());

        JsonNode polygon = TestBuilders.samplePolygon();
        JsonNode lineStrings = TestBuilders.sampleLinestrings();
        BuildResult result = spyBuildPipelineService.buildZone(zoneId, polygon, lineStrings).get();

        Assertions.assertThat(result.ok()).isTrue();
        Assertions.assertThat(result.osrmPort()).isEqualTo(osrmPort);
        Assertions.assertThat(result.vroomPort()).isEqualTo(vroomPort);
        Assertions.assertThat(reduceCalled).isTrue();

        Assertions.assertThat(zoneDir.resolve("lineStrings.geojson")).exists();

        Optional<ZoneEntity> updated = runInTransaction(() -> zoneRepository.findById(zoneId));
        Assertions.assertThat(updated).isPresent();
        Assertions.assertThat(updated.get().getStatus()).isEqualTo(ZoneStatus.BUILT.name());
    }
}
