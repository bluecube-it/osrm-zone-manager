package it.bluecube.osrmzonemanager;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Data
@Validated
@ConfigurationProperties(prefix = "osrm.zone-manager")
public class OsrmZoneManagerConfig {

    /**
     * Routing profile used when a zone request does not specify one.
     */
    public static final String DEFAULT_PROFILE = "car";

    /**
     * Profile names accepted by {@code POST /zones}. Each name must have a matching
     * {@code <name>-lua} property resolving to the OSRM profile script shipped in the image.
     */
    public static final List<String> SUPPORTED_PROFILES = List.of(DEFAULT_PROFILE, "bus");

    @NotBlank
    private String dataDir = "/data";

    @NotBlank
    private String basePbf = "/data/base/italy.osm.pbf";

    @NotBlank
    private String carLua = "/opt/car.lua";

    @NotBlank
    private String busLua = "/opt/bus.lua";

    @NotBlank
    private String vroomExpressDir = "/vroom-express";

    @NotBlank
    private String reduceScript = "/app/scripts/reduce.py";

    private int osrmPortStart = 5000;

    private int vroomPortStart = 3000;

    private int osrmDefaultRadius = 50;

    private boolean osrmMmap = true;

    private long minPbfSize = 1_048_576;

    public String getZonesDir() {
        return dataDir + "/zones";
    }

    /**
     * Normalizes a client-supplied profile name — blank/null falls back to
     * {@link #DEFAULT_PROFILE}; otherwise trimmed and lower-cased.
     *
     * @param profile raw profile name from the request
     * @return the normalized profile name, never blank
     */
    public static String normalizeProfile(String profile) {
        return profile == null || profile.isBlank()
                ? DEFAULT_PROFILE
                : profile.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves the OSRM Lua profile script for the given profile name.
     *
     * @param profile profile name (any case, may be null)
     * @return the absolute path to the Lua profile, or empty if the profile is not mapped
     */
    public Optional<String> profileLuaPath(String profile) {
        return switch (normalizeProfile(profile)) {
            case "car" -> Optional.of(carLua);
            case "bus" -> Optional.of(busLua);
            default -> Optional.empty();
        };
    }
}
