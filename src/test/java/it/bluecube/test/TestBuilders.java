package it.bluecube.test;

import it.bluecube.osrmzonemanager.zone.ZoneEntity;
import it.bluecube.osrmzonemanager.zone.ZoneStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

public class TestBuilders {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public static final String SAMPLE_POLYGON_GEOJSON = """
            {"type":"Polygon","coordinates":[[[0,0],[0,1],[1,1],[1,0],[0,0]]]}""";
    public static final String SAMPLE_LINE_STRINGS_GEOJSON = """
            {"type":"FeatureCollection","features":[]}""";

    public static JsonNode samplePolygon() {
        return readTree(SAMPLE_POLYGON_GEOJSON);
    }

    public static JsonNode sampleLinestrings() {
        return readTree(SAMPLE_LINE_STRINGS_GEOJSON);
    }

    public static ZoneEntity.ZoneEntityBuilder fullyPopulatedZoneEntity() {
        return ZoneEntity.builder()
                .zoneId("testzone123456")
                .polygonHash("abc123")
                .lineStringsHash("")
                .basePbfMtime("12345")
                .status(ZoneStatus.BUILDING.name())
                .osrmPort(5001)
                .vroomPort(3001)
                .osrmPid(0)
                .vroomPid(0)
                .createdAt(Instant.now())
                .lastAccess(Instant.now())
                .lastBuildAt(Instant.now())
                .error("")
                .polygonGeojson(SAMPLE_POLYGON_GEOJSON)
                .lineStringsGeojson(null);
    }

    private static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
