package it.bluecube.osrmzonemanager;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

@Data
@Validated
@ConfigurationProperties(prefix = "osrm.zone-manager")
public class OsrmZoneManagerConfig {

    @NotBlank
    private String dataDir = "/data";

    @NotBlank
    private String basePbf = "/data/base/italy.osm.pbf";

    /**
     * Lua profile script used for {@link it.bluecube.osrmzonemanager.zone.ZoneProfile#CAR}.
     */
    @NotBlank
    private String carLua = "/opt/car.lua";

    /**
     * Lua profile script used for {@link it.bluecube.osrmzonemanager.zone.ZoneProfile#BUS}.
     */
    @NotBlank
    private String busLua = "/opt/bus.lua";

    /**
     * {@code vroom} binary invoked directly for every VROOM request. Kept configurable so tests can
     * point it at a stub.
     */
    @NotBlank
    private String vroomBinary = "vroom";

    /**
     * Number of solving threads handed to the binary ({@code -t}), overridable per request when
     * {@code t} is listed in {@link #vroomOverride}.
     */
    private int vroomThreads = 6;

    /**
     * Exploration level 0..5 handed to the binary ({@code -x}).
     */
    private int vroomExplore = 5;

    /**
     * Default for the {@code -g} flag (return route geometry).
     */
    private boolean vroomGeometry = false;

    /**
     * Default for the {@code -c} flag (choose ETA for custom routes and report violations), which is
     * what {@code vroom-express} called {@code planmode}.
     */
    private boolean vroomChooseEta = false;

    /**
     * Default solving time limit in seconds ({@code -l}); {@code 0} omits the flag.
     */
    private int vroomLimitSeconds = 0;

    /**
     * Wall-clock budget for a single {@code vroom} run, in milliseconds. On expiry the process is killed.
     */
    private long vroomTimeoutMs = 300_000;

    /**
     * Maximum number of locations ({@code jobs + 2 * shipments}) accepted in a VROOM request.
     */
    private int vroomMaxLocations = 1000;

    /**
     * Maximum number of vehicles accepted in a VROOM request.
     */
    private int vroomMaxVehicles = 200;

    /**
     * Maximum accepted VROOM request body size, in bytes.
     */
    private long vroomMaxBodyBytes = 1_048_576;

    /**
     * VROOM solving options a request may override through its {@code options} object
     * ({@code c}, {@code g}, {@code l}, {@code t}, {@code x}).
     */
    private List<String> vroomOverride = new ArrayList<>(List.of("c", "g", "l", "t", "x"));

    /**
     * Maximum number of concurrent {@code vroom} processes. The binary is multi-threaded ({@code -t}),
     * so this bounds CPU oversubscription; keep {@code vroom-threads × vroom-max-concurrent} close to
     * the available cores. Requests above the limit queue instead of being rejected.
     */
    private int vroomMaxConcurrent = 16;

    /**
     * Classpath resource holding the custom-matrix payload used by {@code GET /{zoneId}/vroom/health}.
     */
    @NotBlank
    private String vroomHealthcheckResource = "config/vroom_healthcheck.json";

    @NotBlank
    private String reduceScript = "/app/scripts/reduce.py";

    private int osrmPortStart = 5000;

    private int osrmDefaultRadius = 50;

    private boolean osrmMmap = true;

    private long minPbfSize = 1_048_576;

    public String getZonesDir() {
        return dataDir + "/zones";
    }
}
