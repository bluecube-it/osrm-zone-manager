package it.bluecube.osrmzonemanager.builder;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.zone.ZoneStateService;
import it.bluecube.test.BaseUnitTest;
import it.bluecube.test.TestBuilders;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;

class BuildPipelineServiceBuildZoneTest extends BaseUnitTest {

    @Mock
    private OsrmZoneManagerConfig config;
    @Mock
    private ZoneStateService zoneStateService;
    private ObjectMapper objectMapper;
    private Path zonesDir;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        zonesDir = Files.createTempDirectory("zones");

        Mockito.lenient().when(config.getZonesDir()).thenReturn(zonesDir.toString());
        Mockito.lenient().when(config.getBasePbf()).thenReturn("/tmp/base.pbf");
        Mockito.lenient().when(config.getCarLua()).thenReturn("/tmp/car.lua");
        Mockito.lenient().when(config.getReduceScript()).thenReturn("/tmp/reduce.py");
    }

    @Test
    void shouldReturnFailedResultWhenZoneNotInRegistry() throws Exception {
        BuildPipelineService target = buildService();
        Mockito.when(zoneStateService.findOsrmPort("missing")).thenReturn(Optional.empty());

        BuildResult result = target.buildZone("missing", TestBuilders.samplePolygon(), null).get();

        Assertions.assertThat(result.ok()).isFalse();
        Assertions.assertThat(result.error()).contains("not found in registry");
        verifyNoSemaphoreAcquire(target);
    }

    @Test
    void shouldAcquireAndReleaseBuildSlotOnCompletion() throws Exception {
        BuildPipelineService target = buildService();
        Semaphore semaphore = Mockito.spy(new Semaphore(1));
        ReflectionTestUtils.setField(target, "buildSlots", semaphore);
        Mockito.when(zoneStateService.findOsrmPort("zone")).thenReturn(Optional.of(5001));

        BuildResult result = target.buildZone("zone", TestBuilders.samplePolygon(), null).get();

        Assertions.assertThat(result.ok()).isTrue();
        Mockito.verify(semaphore).acquire();
        Mockito.verify(semaphore).release();
    }

    @Test
    void shouldMarkZoneBuiltOnSuccess() throws Exception {
        BuildPipelineService target = buildService();
        ReflectionTestUtils.setField(target, "buildSlots", new Semaphore(1));
        Mockito.when(zoneStateService.findOsrmPort("zone")).thenReturn(Optional.of(5001));

        BuildResult result = target.buildZone("zone", TestBuilders.samplePolygon(), null).get();

        Assertions.assertThat(result.ok()).isTrue();
        Mockito.verify(zoneStateService).markZoneBuilt("zone");
    }

    @Test
    void shouldMarkZoneFailedAndReleasePortsOnException() throws Exception {
        BuildPipelineService target = new BuildPipelineService(config, zoneStateService, objectMapper, new OsrmCommandRunner(config)) {
            @Override
            protected void runSubprocess(List<String> command, File cwd) {
                throw new BuildException("boom");
            }
        };
        Semaphore semaphore = Mockito.spy(new Semaphore(1));
        ReflectionTestUtils.setField(target, "buildSlots", semaphore);
        Mockito.when(zoneStateService.findOsrmPort("zone")).thenReturn(Optional.of(5001));

        BuildResult result = target.buildZone("zone", TestBuilders.samplePolygon(), null).get();

        Assertions.assertThat(result.ok()).isFalse();
        Assertions.assertThat(result.error()).contains("boom");
        Mockito.verify(zoneStateService).markZoneFailed("zone", "boom");
        Mockito.verify(semaphore).release();
    }

    @Test
    void shouldReturnInterruptedResultWhenSlotAcquireInterrupted() throws Exception {
        BuildPipelineService target = buildService();
        Semaphore semaphore = Mockito.mock(Semaphore.class);
        Mockito.doThrow(new InterruptedException()).when(semaphore).acquire();
        ReflectionTestUtils.setField(target, "buildSlots", semaphore);
        Mockito.when(zoneStateService.findOsrmPort("zone")).thenReturn(Optional.of(5001));

        BuildResult result = target.buildZone("zone", TestBuilders.samplePolygon(), null).get();

        Assertions.assertThat(result.ok()).isFalse();
        Assertions.assertThat(result.error()).containsIgnoringCase("interrupted");
        Mockito.verify(zoneStateService, Mockito.never()).markZoneBuilt(Mockito.anyString());
    }

    private BuildPipelineService buildService() {
        return new BuildPipelineService(config, zoneStateService, objectMapper, new OsrmCommandRunner(config)) {
            @Override
            protected void runSubprocess(List<String> command, File cwd) throws IOException {
                if ("osmium".equals(command.get(0)) && "extract".equals(command.get(1))) {
                    int idx = command.indexOf("-o");
                    if (idx >= 0 && idx + 1 < command.size()) {
                        Path out = Path.of(command.get(idx + 1));
                        Files.createDirectories(out.getParent());
                        if (!Files.exists(out)) {
                            Files.createFile(out);
                        }
                    }
                }
            }
        };
    }

    private void verifyNoSemaphoreAcquire(BuildPipelineService target) {
        Semaphore semaphore = (Semaphore) ReflectionTestUtils.getField(target, "buildSlots");
        // default initial permits = MAX_CONCURRENT_BUILDS; no acquire/release happened
        Assertions.assertThat(semaphore.availablePermits()).isEqualTo(3);
    }
}
