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
 * silently ignored, while an allowed key carrying an unexpected type is ignored with a warning (the
 * configured default wins).
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
            if (allowed.contains("g")) {
                geometry = booleanOption(options, "g", geometry);
            }
            if (allowed.contains("c")) {
                chooseEta = booleanOption(options, "c", chooseEta);
            }
            if (allowed.contains("t")) {
                threads = intOption(options, "t", threads);
            }
            if (allowed.contains("x")) {
                explore = intOption(options, "x", explore);
            }
            if (allowed.contains("l")) {
                limitSeconds = intOption(options, "l", limitSeconds);
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

    /**
     * Reads a boolean option, ignoring values of any other type with a warning.
     *
     * @param options options object from the request
     * @param key     option key ({@code g} or {@code c})
     * @param current configured default, used when the option is absent or mistyped
     * @return the requested value, or {@code current}
     */
    private boolean booleanOption(JsonNode options, String key, boolean current) {
        JsonNode value = options.path(key);
        if (value.isMissingNode() || value.isNull()) {
            return current;
        }
        if (!value.isBoolean()) {
            log.warn("Ignoring VROOM option '{}' with unexpected type (expected boolean, got {}), keeping {}",
                    key, value, current);
            return current;
        }
        return value.asBoolean();
    }

    /**
     * Reads an integer option, ignoring values of any other type with a warning.
     *
     * @param options options object from the request
     * @param key     option key ({@code t}, {@code x} or {@code l})
     * @param current configured default, used when the option is absent or mistyped
     * @return the requested value, or {@code current}
     */
    private int intOption(JsonNode options, String key, int current) {
        JsonNode value = options.path(key);
        if (value.isMissingNode() || value.isNull()) {
            return current;
        }
        if (!value.isIntegralNumber()) {
            log.warn("Ignoring VROOM option '{}' with unexpected type (expected integer, got {}), keeping {}",
                    key, value, current);
            return current;
        }
        return value.asInt();
    }
}
