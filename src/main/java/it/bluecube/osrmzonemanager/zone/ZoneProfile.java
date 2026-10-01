package it.bluecube.osrmzonemanager.zone;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Routing profiles the manager can build zones for.
 *
 * <p>OSRM bakes the profile into the preprocessed graph, so the profile is chosen when a zone is
 * created — there is no query-time switch. Names are carried over the API, stored in the registry
 * and reported in responses as the enum names ({@code CAR}, {@code BUS}). The lower-case spelling
 * only appears in the Lua script file names configured for each profile.
 */
public enum ZoneProfile {
    /**
     * Default profile, built from {@code car.lua}.
     */
    CAR,
    /**
     * Bus profile, built from {@code bus.lua} (overlay on {@code car.lua}).
     */
    BUS;

    /**
     * Parses a profile name case-insensitively.
     *
     * @param value profile name ({@code CAR}, {@code BUS}, any case); may be null
     * @return the matching profile, or {@code null} if {@code value} is null
     * @throws IllegalArgumentException if the value does not match any profile
     */
    @JsonCreator
    public static ZoneProfile fromValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(profile -> profile.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unsupported profile '" + value + "' — supported: " + supportedValues()));
    }

    /**
     * @return the accepted profile names, comma-separated (for error messages and docs)
     */
    public static String supportedValues() {
        return Arrays.stream(values()).map(ZoneProfile::name).collect(Collectors.joining(", "));
    }
}
