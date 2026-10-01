package it.bluecube.osrmzonemanager.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Fingerprint of a preprocessed OSRM map directory: which OSRM build produced it and which dataset it
 * was built from.
 *
 * <p>OSRM bakes both the file layout and a binary fingerprint into the graph, and refuses to load a
 * map produced by a different OSRM release:
 *
 * <pre>Unexpected end of file: osrm_fingerprint.meta: Datatype size does not match file size.</pre>
 *
 * <p>That is a hard failure that only a <em>rebuild</em> can fix, so every zone and every global
 * whole-map directory carries a {@value #MARKER_FILE} sidecar written at the end of a successful
 * build. Recovery/startup paths compare it against the OSRM version currently installed (and, for
 * globals, the base PBF mtime) and rebuild instead of trying to load a stale map.
 *
 * <p>Historically the registry only tracked the base PBF mtime, so bumping the OSRM version in the
 * image (v26.4 → v26.10.0) turned every existing zone into a permanently {@code FAILED} zone: the
 * files were all there, the map was simply unreadable.
 */
@Slf4j
@Component
public class OsrmMapFingerprint {

    /**
     * Sidecar file written next to {@code map.osrm.properties} after a successful build.
     */
    public static final String MARKER_FILE = "map.fingerprint";

    /**
     * Artifacts {@code osrm-routed} needs to load an MLD graph. Used as a cheap completeness check
     * before attempting a start (a truncated/partial set otherwise costs a full startup timeout).
     */
    public static final List<String> REQUIRED_MAP_FILES = List.of(
            "map.osrm.properties",
            "map.osrm.fileIndex",
            "map.osrm.ramIndex",
            "map.osrm.mldgr",
            "map.osrm.partition",
            "map.osrm.cells",
            "map.osrm.cell_metrics",
            "map.osrm.cnbg",
            "map.osrm.ebg",
            "map.osrm.geometry",
            "map.osrm.edges"
    );

    private static final String BINARY_OSRM_EXTRACT = "osrm-extract";
    private static final String FLAG_VERSION = "--version";
    private static final int VERSION_TIMEOUT_SECONDS = 15;
    private static final String KEY_OSRM_VERSION = "osrm-version=";
    private static final String KEY_FINGERPRINT = "fingerprint=";
    private static final String UNKNOWN = "unknown";

    private volatile String cachedOsrmVersion;

    /**
     * @return the version of the {@code osrm-extract} installed in the image (first line of
     * {@code --version}), or {@code unknown} when the binary cannot be executed. Cached: the
     * container's binaries never change while the JVM runs.
     */
    public String osrmVersion() {
        String version = cachedOsrmVersion;
        if (version == null) {
            synchronized (this) {
                if (cachedOsrmVersion == null) {
                    cachedOsrmVersion = detectOsrmVersion();
                }
                version = cachedOsrmVersion;
            }
        }
        return version;
    }

    /**
     * Writes the fingerprint marker for a freshly built map directory.
     *
     * @param mapDir             directory holding {@code map.osrm.*}
     * @param datasetFingerprint caller-specific dataset identity (for example the base PBF mtime);
     *                           use an empty string when the directory itself identifies the dataset
     * @throws IOException if the marker cannot be written
     */
    public void write(Path mapDir, String datasetFingerprint) throws IOException {
        Files.writeString(mapDir.resolve(MARKER_FILE), markerContent(osrmVersion(), datasetFingerprint));
    }

    /**
     * @param mapDir             directory holding {@code map.osrm.*}
     * @param datasetFingerprint dataset identity the caller expects
     * @return {@code true} when the marker exists and matches the current OSRM version and dataset
     */
    public boolean matches(Path mapDir, String datasetFingerprint) {
        Path marker = mapDir.resolve(MARKER_FILE);
        if (!Files.exists(marker)) {
            return false;
        }
        try {
            return Files.readString(marker).equals(markerContent(osrmVersion(), datasetFingerprint));
        } catch (IOException e) {
            log.warn("Cannot read {}: {}", marker, e.getMessage());
            return false;
        }
    }

    /**
     * @param mapDir directory holding {@code map.osrm.*}
     * @return {@code true} when every artifact required to load the MLD graph is present
     */
    public boolean isMapComplete(Path mapDir) {
        return REQUIRED_MAP_FILES.stream().allMatch(file -> Files.exists(mapDir.resolve(file)));
    }

    /**
     * Combined check used before starting an instance: complete artifacts, built by the installed
     * OSRM version, from the expected dataset.
     *
     * @param mapDir             directory holding {@code map.osrm.*}
     * @param datasetFingerprint dataset identity the caller expects
     * @return {@code true} when the map can be loaded
     */
    public boolean isUsable(Path mapDir, String datasetFingerprint) {
        return isMapComplete(mapDir) && matches(mapDir, datasetFingerprint);
    }

    /**
     * Dataset identity of a source PBF: its last-modified time. A replaced PBF invalidates every map
     * built from it, exactly like a changed polygon does for a zone.
     *
     * @param pbf path to the base PBF
     * @return the mtime in milliseconds, or {@code unknown} when it cannot be read
     */
    public String pbfFingerprint(Path pbf) {
        try {
            return String.valueOf(Files.getLastModifiedTime(pbf).toMillis());
        } catch (IOException e) {
            log.warn("Cannot read the last-modified time of {}: {}", pbf, e.getMessage());
            return UNKNOWN;
        }
    }

    private String markerContent(String osrmVersion, String datasetFingerprint) {
        return KEY_OSRM_VERSION + osrmVersion + System.lineSeparator()
                + KEY_FINGERPRINT + datasetFingerprint + System.lineSeparator();
    }

    private String detectOsrmVersion() {
        try {
            Process process = new ProcessBuilder(BINARY_OSRM_EXTRACT, FLAG_VERSION)
                    .redirectErrorStream(true)
                    .start();
            String line;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                line = reader.readLine();
            }
            if (!process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("{} {} timed out, OSRM version unknown", BINARY_OSRM_EXTRACT, FLAG_VERSION);
                return UNKNOWN;
            }
            if (line == null || line.isBlank()) {
                log.warn("{} {} produced no output, OSRM version unknown", BINARY_OSRM_EXTRACT, FLAG_VERSION);
                return UNKNOWN;
            }
            return line.trim();
        } catch (IOException e) {
            log.warn("Cannot detect the OSRM version ({}): {}", e.getMessage(), UNKNOWN);
            return UNKNOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return UNKNOWN;
        }
    }
}
