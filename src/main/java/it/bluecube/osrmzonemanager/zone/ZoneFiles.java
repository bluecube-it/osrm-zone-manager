package it.bluecube.osrmzonemanager.zone;

/**
 * Shared file-name constants for zone files stored on disk.
 *
 * <p>Each zone gets a directory under {@code config.getZonesDir()}; the files
 * inside follow a fixed naming convention referenced by build, recovery, and
 * proxy layers.
 */
public final class ZoneFiles {
    private ZoneFiles() {}

    /** OSRM map metadata produced by the build pipeline; its presence means the zone is built. */
    public static final String MAP_OSRM_PROPERTIES = "map.osrm.properties";

    /** Source polygon GeoJSON persisted at zone creation time, used for hash verification on recovery. */
    public static final String POLYGON_GEOJSON = "polygon.geojson";

    /** Source lineStrings GeoJSON persisted at zone creation time, used for custom ways merge during build. */
    public static final String LINE_STRINGS_GEOJSON = "lineStrings.geojson";
}
