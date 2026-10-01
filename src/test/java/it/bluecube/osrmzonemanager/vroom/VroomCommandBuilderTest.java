package it.bluecube.osrmzonemanager.vroom;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.zone.ZoneProfile;
import it.bluecube.test.BaseUnitTest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

class VroomCommandBuilderTest extends BaseUnitTest {

    private VroomCommandBuilder builder;

    @BeforeEach
    void setUp() {
        OsrmZoneManagerConfig config = Mockito.mock(OsrmZoneManagerConfig.class);
        Mockito.lenient().when(config.getVroomBinary()).thenReturn("vroom");
        Mockito.lenient().when(config.getVroomThreads()).thenReturn(4);
        Mockito.lenient().when(config.getVroomExplore()).thenReturn(5);
        builder = new VroomCommandBuilder(config);
    }

    @Test
    void carZoneRegistersOnlyCarProfile() {
        List<String> command = builder.build(ZoneProfile.CAR, 5001,
                new VroomOptions(4, 5, false, false, 0));

        Assertions.assertThat(command).containsExactly(
                "vroom", "-r", "osrm",
                "-a", "car:127.0.0.1", "-p", "car:5001",
                "-t", "4", "-x", "5");
    }

    @Test
    void busZoneRegistersBusProfileAndCarAlias() {
        List<String> command = builder.build(ZoneProfile.BUS, 5100,
                new VroomOptions(4, 5, false, false, 0));

        Assertions.assertThat(command).containsSubsequence("-a", "car:127.0.0.1", "-p", "car:5100");
        Assertions.assertThat(command).containsSubsequence("-a", "bus:127.0.0.1", "-p", "bus:5100");
    }

    @Test
    void nullProfileFallsBackToCar() {
        Assertions.assertThat(VroomCommandBuilder.vroomProfileNames(null)).containsExactly("car");
    }

    @Test
    void flagsFollowResolvedOptions() {
        List<String> command = builder.build(ZoneProfile.CAR, 5001,
                new VroomOptions(8, 2, true, true, 30));

        Assertions.assertThat(command).containsSubsequence("-c", "-g");
        Assertions.assertThat(command).containsSubsequence("-t", "8");
        Assertions.assertThat(command).containsSubsequence("-x", "2");
        Assertions.assertThat(command).containsSubsequence("-l", "30");
    }

    @Test
    void zeroLimitOmitsLimitFlag() {
        List<String> command = builder.build(ZoneProfile.CAR, 5001,
                new VroomOptions(4, 5, false, false, 0));

        Assertions.assertThat(command).doesNotContain("-l");
    }

    @Test
    void healthcheckUsesZoneOsrmPortWithoutSolvingOverrides() {
        List<String> command = builder.buildHealthcheck(ZoneProfile.BUS, 5555);

        Assertions.assertThat(command).containsSubsequence("-a", "bus:127.0.0.1", "-p", "bus:5555");
        Assertions.assertThat(command).doesNotContain("-g", "-c", "-l");
    }
}
