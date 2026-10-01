package it.bluecube.osrmzonemanager.vroom;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves the effective {@link VroomOptions} for a request: configured defaults, optionally
 * overridden by the client-provided {@code options} object — but only for the keys listed in
 * {@code osrm.zone-manager.vroom-override} ({@code c}, {@code g}, {@code l}, {@code t}, {@code x} by default).
 *
 * <p>Mirrors the {@code override} handling of {@code vroom-express}: unknown or non-allowed keys are
 * silently ignored, as are non-numeric values for numeric options.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VroomOptionsResolver {

    private final OsrmZoneManagerConfig config;

    /**
     * @return the compiled-in default override keys, for docs and tests
     */
    public static Set<String> defaultOverrides() {
        return Set.copyOf(Arrays.asList("c", "g", "l", "t", "x"));
    }

    /**
     * @param body parsed VROOM request payload
     * @return the effective options for this request
     */
    public VroomOptions resolve(JsonNode body) {
        Set<String> allowed = allowedOverrides();

        int threads = config.getVroomThreads();
        int explore = config.getVroomExplore();
        boolean geometry = config.isVroomGeometry();
        boolean chooseEta = config.isVroomChooseEta();
        int limitSeconds = config.getVroomLimitSeconds();

        JsonNode options = body.path("options");
        if (options.isObject()) {
            if (allowed.contains("g") && options.path("g").isBoolean()) {
                geometry = options.path("g").asBoolean();
            }
            if (allowed.contains("c") && options.path("c").isBoolean()) {
                chooseEta = options.path("c").asBoolean();
            }
            if (allowed.contains("t") && options.path("t").isIntegralNumber()) {
                threads = options.path("t").asInt();
            }
            if (allowed.contains("x") && options.path("x").isIntegralNumber()) {
                explore = options.path("x").asInt();
            }
            if (allowed.contains("l") && options.path("l").isIntegralNumber()) {
                limitSeconds = options.path("l").asInt();
            }
        }

        return new VroomOptions(
                sanitize(threads, config.getVroomThreads()),
                sanitize(explore, config.getVroomExplore()),
                geometry,
                chooseEta,
                Math.max(limitSeconds, 0)
        );
    }

    /**
     * @return the configured override keys, lower-cased; never null
     */
    Set<String> allowedOverrides() {
        if (config.getVroomOverride() == null) {
            return Set.of();
        }
        Set<String> allowed = new LinkedHashSet<>();
        config.getVroomOverride().stream()
                .filter(java.util.Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .forEach(allowed::add);
        return allowed;
    }

    private int sanitize(int requested, int fallback) {
        if (requested < 0) {
            log.warn("Ignoring negative vroom option value {}, falling back to {}", requested, fallback);
            return fallback;
        }
        return requested;
    }
}
