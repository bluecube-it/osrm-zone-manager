package it.bluecube.osrmzonemanager.vroom;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.zone.ZoneProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the {@code vroom} command line for a zone.
 *
 * <p>The routing server is bound per zone: the zone's OSRM instance is registered under the
 * VROOM profile name derived from the zone's persisted {@link ZoneProfile} ({@code car} / {@code bus}).
 * Because VROOM defaults an unset vehicle {@code profile} to {@code car}, the {@code car} name is
 * always registered as an alias for backward compatibility with clients that omit the profile.
 *
 * <p>Input is read from stdin (no {@code -i} flag) — VROOM consumes stdin when no input file or
 * positional argument is given, which removes the temporary JSON file that {@code vroom-express}
 * wrote for every request.
 */
@Component
@RequiredArgsConstructor
public class VroomCommandBuilder {

    private static final String DEFAULT_VROOM_PROFILE = "car";
    private static final String ROUTER_OSRM = "osrm";
    private static final String LOCALHOST = "127.0.0.1";

    private final OsrmZoneManagerConfig config;

    /**
     * Builds the argv for a solving run.
     *
     * @param profile  zone routing profile ({@code null} is treated as {@link ZoneProfile#CAR})
     * @param osrmPort loopback port of the zone's {@code osrm-routed}
     * @param options  resolved per-request options
     * @return the full command line, binary included
     */
    public List<String> build(ZoneProfile profile, int osrmPort, VroomOptions options) {
        List<String> command = new ArrayList<>();
        command.add(config.getVroomBinary());
        command.add("-r");
        command.add(ROUTER_OSRM);
        for (String profileName : vroomProfileNames(profile)) {
            command.add("-a");
            command.add(profileName + ":" + LOCALHOST);
            command.add("-p");
            command.add(profileName + ":" + osrmPort);
        }
        if (options.chooseEta()) {
            command.add("-c");
        }
        if (options.geometry()) {
            command.add("-g");
        }
        command.add("-t");
        command.add(String.valueOf(options.threads()));
        command.add("-x");
        command.add(String.valueOf(options.explore()));
        if (options.limitSeconds() > 0) {
            command.add("-l");
            command.add(String.valueOf(options.limitSeconds()));
        }
        return command;
    }

    /**
     * Builds the argv for the health probe: same routing servers, no solving options.
     *
     * @param profile  zone routing profile ({@code null} is treated as {@link ZoneProfile#CAR})
     * @param osrmPort loopback port of the zone's {@code osrm-routed}
     * @return the full command line, binary included
     */
    public List<String> buildHealthcheck(ZoneProfile profile, int osrmPort) {
        return build(profile, osrmPort, new VroomOptions(config.getVroomThreads(), config.getVroomExplore(),
                false, false, 0));
    }

    /**
     * Maps a zone profile to the VROOM routing-server names to register.
     *
     * <p>{@code car} is always included: VROOM defaults to that profile name for vehicles without an
     * explicit {@code profile} key, and zones built before profiles existed only ever exposed {@code car}.
     *
     * @param profile zone routing profile ({@code null} is treated as {@link ZoneProfile#CAR})
     * @return profile names, {@code car} first
     */
    static List<String> vroomProfileNames(ZoneProfile profile) {
        ZoneProfile resolved = profile == null ? ZoneProfile.CAR : profile;
        String name = resolved.name().toLowerCase(Locale.ROOT);
        if (DEFAULT_VROOM_PROFILE.equals(name)) {
            return List.of(DEFAULT_VROOM_PROFILE);
        }
        return List.of(DEFAULT_VROOM_PROFILE, name);
    }
}
