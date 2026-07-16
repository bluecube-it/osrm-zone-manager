package it.bluecube.osrmzonemanager;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "osrm.zone-manager")
public class OsrmZoneManagerConfig {

    @NotBlank
    private String configDir = "/config";

    @NotBlank
    private String dataDir = "/data";

    @NotBlank
    private String basePbf = "/data/base/italy.osm.pbf";

    @NotBlank
    private String geofabrikUrl = "https://download.geofabrik.de/europe/italy-latest.osm.pbf";

    @NotBlank
    private String carLua = "/opt/car.lua";

    @NotBlank
    private String vroomExpressDir = "/vroom-express";

    @NotBlank
    private String reduceScript = "/app/scripts/reduce.py";

    @Positive
    private int zoneTtlDays = 90;

    @Positive
    private int maxActiveZones = 20;

    private int osrmPortStart = 5000;

    private int vroomPortStart = 3000;

    private int osrmDefaultRadius = 50;

    @Positive
    private int evictorIntervalMinutes = 10;

    private boolean osrmMmap = true;

    private long minPbfSize = 1;

    public String getZonesDir() {
        return dataDir + "/zones";
    }
}
