package it.bluecube.osrmzonemanager.global;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.builder.BuildSerializer;
import it.bluecube.osrmzonemanager.builder.OsrmCommandRunner;
import it.bluecube.osrmzonemanager.maps.MapsService;
import it.bluecube.osrmzonemanager.runtime.OsrmMapFingerprint;
import it.bluecube.osrmzonemanager.runtime.OsrmProcessLauncher;
import it.bluecube.osrmzonemanager.runtime.PortAllocatorService;
import it.bluecube.osrmzonemanager.zone.ZoneProfile;
import it.bluecube.test.BaseUnitTest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.boot.DefaultApplicationArguments;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

class GlobalOsrmServiceTest extends BaseUnitTest {

    @Mock
    private OsrmZoneManagerConfig config;
    @Mock
    private MapsService mapsService;
    @Mock
    private OsrmCommandRunner commandRunner;
    @Mock
    private OsrmProcessLauncher launcher;
    @Mock
    private PortAllocatorService portAllocator;

    private final GlobalOsrmRegistry registry = new GlobalOsrmRegistry();
    private final OsrmMapFingerprint mapFingerprint = new OsrmMapFingerprint();
    private final Executor executor = Runnable::run;
    private final AtomicInteger portSequence = new AtomicInteger(5000);

    private Path globalDir;
    private Path basePbf;
    private GlobalOsrmService service;

    @BeforeEach
    void setUp() throws Exception {
        globalDir = Files.createTempDirectory("global-osrm");
        basePbf = Files.createFile(globalDir.resolve("italy.osm.pbf"));

        Mockito.lenient().when(config.isGlobalOsrmEnabled()).thenReturn(true);
        Mockito.lenient().when(config.getGlobalBuildTimeoutSeconds()).thenReturn(30);
        Mockito.lenient().when(config.getCarLua()).thenReturn("/opt/car.lua");
        Mockito.lenient().when(config.getBusLua()).thenReturn("/opt/bus.lua");
        Mockito.lenient().when(config.getGlobalProfileDir(ArgumentMatchers.any())).thenAnswer(invocation ->
                globalDir.resolve(((ZoneProfile) invocation.getArgument(0)).name().toLowerCase()).toString());
        Mockito.lenient().when(mapsService.ensureBasePbf()).thenReturn(basePbf.toString());
        Mockito.lenient().when(commandRunner.profileLuaPath(ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0) == ZoneProfile.BUS ? "/opt/bus.lua" : "/opt/car.lua");
        Mockito.lenient().when(portAllocator.reservePort(ArgumentMatchers.any())).thenAnswer(invocation -> {
            int port = portSequence.incrementAndGet();
            invocation.getArgument(0, Consumer.class).accept(port);
            return port;
        });
        Mockito.lenient().when(launcher.launch(ArgumentMatchers.any(), ArgumentMatchers.anyInt()))
                .thenReturn(Mockito.mock(Process.class));
        Mockito.lenient().when(launcher.waitRouteHealth(ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt()))
                .thenReturn(true);

        service = new GlobalOsrmService(config, mapsService, commandRunner, new BuildSerializer(), launcher,
                mapFingerprint, portAllocator, registry, executor);
    }

    @Test
    void shouldBuildWholeMapPerProfileAndServeIt() throws Exception {
        service.startAll();

        Assertions.assertThat(registry.statuses()).hasSize(ZoneProfile.values().length)
                .allSatisfy(status -> Assertions.assertThat(status.status()).isEqualTo(GlobalOsrmStatus.READY.name()));
        Assertions.assertThat(service.findPort(ZoneProfile.CAR)).contains(5001);
        Assertions.assertThat(service.findPort(ZoneProfile.BUS)).contains(5002);

        List<List<String>> commands = capturedCommands();
        Assertions.assertThat(commands).hasSize(6);
        Assertions.assertThat(commands.get(0)).containsExactly("osrm-extract", "-p", "/opt/car.lua",
                "-o", globalDir.resolve("car/map").toString(), basePbf.toString());
        Assertions.assertThat(commands.get(1)).containsExactly("osrm-partition", "map.osrm");
        Assertions.assertThat(commands.get(2)).containsExactly("osrm-customize", "map.osrm");
        Assertions.assertThat(commands.get(5)).containsExactly("osrm-customize", "map.osrm");
        Assertions.assertThat(commands.get(3).get(2)).isEqualTo("/opt/bus.lua");
        Assertions.assertThat(globalDir.resolve("car").resolve(OsrmMapFingerprint.MARKER_FILE)).exists();
    }

    @Test
    void shouldRegisterEveryProfileAsPendingBeforeBuilding() throws Exception {
        List<String> statusesWhenFirstBuildStarts = new ArrayList<>();
        Mockito.doAnswer(invocation -> {
            if (statusesWhenFirstBuildStarts.isEmpty()) {
                statusesWhenFirstBuildStarts.add(service.statusOf(ZoneProfile.CAR).name());
                statusesWhenFirstBuildStarts.add(service.statusOf(ZoneProfile.BUS).name());
            }
            return null;
        }).when(commandRunner).run(ArgumentMatchers.anyList(), ArgumentMatchers.any(File.class),
                ArgumentMatchers.anyInt());

        service.startAll();

        Assertions.assertThat(statusesWhenFirstBuildStarts)
                .containsExactly(GlobalOsrmStatus.BUILDING.name(), GlobalOsrmStatus.PENDING.name());
    }

    @Test
    void shouldSkipBuildWhenGraphIsUpToDate() throws Exception {
        writeUpToDateGraph(ZoneProfile.CAR);
        writeUpToDateGraph(ZoneProfile.BUS);

        service.startAll();

        Mockito.verify(commandRunner, Mockito.never())
                .run(ArgumentMatchers.anyList(), ArgumentMatchers.any(), ArgumentMatchers.anyInt());
        Assertions.assertThat(service.findPort(ZoneProfile.CAR)).contains(5001);
    }

    @Test
    void shouldRebuildWhenMapWasBuiltByAnotherOsrmVersion() throws Exception {
        writeUpToDateGraph(ZoneProfile.CAR);
        writeUpToDateGraph(ZoneProfile.BUS);
        for (ZoneProfile profile : ZoneProfile.values()) {
            Path marker = globalDir.resolve(profile.name().toLowerCase()).resolve(OsrmMapFingerprint.MARKER_FILE);
            Files.writeString(marker, Files.readString(marker).replaceFirst("osrm-version=.*", "osrm-version=osrm-extract 26.4"));
        }

        service.startAll();

        Assertions.assertThat(capturedCommands()).hasSize(6);
    }

    @Test
    void shouldReportFailedAndUnavailableWhenStartFails() throws Exception {
        Mockito.when(launcher.launch(ArgumentMatchers.any(), ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("no osrm-routed on PATH"));

        service.startAll();

        Assertions.assertThat(service.statusOf(ZoneProfile.CAR)).isEqualTo(GlobalOsrmStatus.FAILED);
        Assertions.assertThat(service.findPort(ZoneProfile.CAR)).isEmpty();
        Assertions.assertThat(service.errorOf(ZoneProfile.CAR)).contains("no osrm-routed on PATH");
    }

    @Test
    void shouldReportFailedWhenStartupTimesOut() throws Exception {
        Mockito.when(launcher.waitRouteHealth(ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt()))
                .thenReturn(false);

        service.startAll();

        Assertions.assertThat(service.statusOf(ZoneProfile.CAR)).isEqualTo(GlobalOsrmStatus.FAILED);
        Assertions.assertThat(service.errorOf(ZoneProfile.CAR)).contains("startup timeout");
    }

    @Test
    void shouldDoNothingWhenDisabled() {
        Mockito.when(config.isGlobalOsrmEnabled()).thenReturn(false);

        service.run(new DefaultApplicationArguments(new String[0]));

        Assertions.assertThat(service.statuses()).hasSize(ZoneProfile.values().length)
                .allSatisfy(status -> Assertions.assertThat(status.status())
                        .isEqualTo(GlobalOsrmStatus.DISABLED.name()));
        Assertions.assertThat(service.findPort(ZoneProfile.CAR)).isEmpty();
        Mockito.verify(mapsService, Mockito.never()).ensureBasePbf();
    }

    @Test
    void shouldMarkDegradedAfterRepeatedUnhealthyChecks() throws Exception {
        service.startAll();
        Mockito.when(launcher.ping(ArgumentMatchers.anyInt())).thenReturn(false);
        Mockito.when(launcher.waitRouteHealth(ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt()))
                .thenReturn(false);

        for (int i = 0; i < 4; i++) {
            service.healthCheck();
        }

        Assertions.assertThat(service.statusOf(ZoneProfile.CAR)).isEqualTo(GlobalOsrmStatus.DEGRADED);
        Assertions.assertThat(service.findPort(ZoneProfile.CAR)).contains(5001);
    }

    @Test
    void shouldKillProcessesOnShutdown() throws Exception {
        service.startAll();

        service.stopAll();

        Mockito.verify(launcher, Mockito.atLeast(2))
                .kill(ArgumentMatchers.any(), ArgumentMatchers.startsWith("global-osrm-"));
    }

    private void writeUpToDateGraph(ZoneProfile profile) throws Exception {
        Path dir = globalDir.resolve(profile.name().toLowerCase());
        Files.createDirectories(dir);
        for (String file : OsrmMapFingerprint.REQUIRED_MAP_FILES) {
            Files.createFile(dir.resolve(file));
        }
        mapFingerprint.write(dir, mapFingerprint.pbfFingerprint(basePbf));
    }

    private List<List<String>> capturedCommands() throws Exception {
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.captor();
        Mockito.verify(commandRunner, Mockito.atLeastOnce())
                .run(captor.capture(), ArgumentMatchers.any(File.class), ArgumentMatchers.anyInt());
        return captor.getAllValues();
    }
}
