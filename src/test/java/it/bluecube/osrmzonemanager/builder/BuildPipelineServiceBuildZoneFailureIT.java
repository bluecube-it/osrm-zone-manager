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
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import org.assertj.core.api.Assertions;

class BuildPipelineServiceBuildZoneFailureIT extends BaseIT {

    @Autowired
    private BuildPipelineService buildPipelineService;

    @Autowired
    private ZoneStateService zoneStateService;

    @Autowired
    private ZoneRepository zoneRepository;

    @Autowired
    private OsrmZoneManagerConfig config;

    @MockitoSpyBean
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
                            } catch (Exception _) {
                                // nothing to do
                            }
                        });
            }
        }
    }

    @Test
    void shouldMarkZoneFailedAndReleasePortsOnException() throws Exception {
        String zoneId = "test-build-fail-1";
        int osrmPort = 5001;
        int vroomPort = 3001;

        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId(zoneId)
                .status(ZoneStatus.BUILDING.name())
                .osrmPort(osrmPort)
                .vroomPort(vroomPort)
                .build();
        runInTransaction(() -> zoneRepository.save(zone));

        Mockito.doThrow(new BuildException("subprocess boom"))
                .when(spyBuildPipelineService).runSubprocess(ArgumentMatchers.anyList(), ArgumentMatchers.any());

        JsonNode polygon = TestBuilders.samplePolygon();
        BuildResult result = spyBuildPipelineService.buildZone(zoneId, polygon, null).get();

        Assertions.assertThat(result.ok()).isFalse();
        Assertions.assertThat(result.error()).contains("subprocess boom");
        Assertions.assertThat(result.osrmPort()).isEqualTo(osrmPort);
        Assertions.assertThat(result.vroomPort()).isEqualTo(vroomPort);

        Optional<ZoneEntity> updated = runInTransaction(() -> zoneRepository.findById(zoneId));
        Assertions.assertThat(updated).isPresent();
        Assertions.assertThat(updated.get().getStatus()).isEqualTo(ZoneStatus.FAILED.name());
        Assertions.assertThat(updated.get().getError()).contains("subprocess boom");

        Mockito.verify(portAllocator).releasePort(ArgumentMatchers.eq("osrm"), ArgumentMatchers.eq(osrmPort));
        Mockito.verify(portAllocator).releasePort(ArgumentMatchers.eq("vroom"), ArgumentMatchers.eq(vroomPort));

        Semaphore semaphore = (Semaphore) ReflectionTestUtils.getField(spyBuildPipelineService, "buildSlots");
        Assertions.assertThat(semaphore.availablePermits()).isEqualTo(3);
    }
}
