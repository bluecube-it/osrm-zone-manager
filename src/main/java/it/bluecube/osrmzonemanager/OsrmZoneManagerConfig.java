package it.bluecube.osrmzonemanager;

import jakarta.validation.constraints.NotBlank;
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
    private String carLua = "/opt/car.lua";

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
}
