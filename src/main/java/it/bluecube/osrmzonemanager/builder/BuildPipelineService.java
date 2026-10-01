package it.bluecube.osrmzonemanager.builder;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.runtime.OsrmMapFingerprint;
import it.bluecube.osrmzonemanager.zone.ZoneFiles;
import it.bluecube.osrmzonemanager.zone.ZoneProfile;
import it.bluecube.osrmzonemanager.zone.ZoneStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/**
 * Orchestrates the build pipeline for a single OSRM zone.
 * Pipeline stages: osmium extract → (reduce.py merge) → osrm-extract → osrm-partition → osrm-customize.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuildPipelineService {

    private static final int SUBPROCESS_TIMEOUT_SECONDS = 600;
    private static final int MAX_CONCURRENT_BUILDS = 3;
    private static final String FILE_REGION_PBF = "region.osm.pbf";
    private static final String FILE_CUSTOM_WAYS_PBF = "custom_ways.pbf";
    private static final String FILE_COMBINED_PBF = "combined.osm.pbf";
    private static final String FILE_OSRM_MAP_BASE = "map.osrm";
    private static final String FILE_OSRM_MAP_OUTPUT = "map";
    private static final String BINARY_OS_RM_EXTRACT = "osrm-extract";
    private static final String BINARY_OS_RM_PARTITION = "osrm-partition";
    private static final String BINARY_OS_RM_CUSTOMIZE = "osrm-customize";
    private static final String CMD_OSMIUM = "osmium";
    private static final String CMD_PYTHON = "python3";
    private static final String FLAG_EXTRACT = "extract";
    private static final String FLAG_MERGE = "merge";
    private static final String FLAG_P = "-p";
    private static final String FLAG_O = "-o";
    private static final String FLAG_OVERWRITE = "--overwrite";
    private final OsrmZoneManagerConfig config;
    private final ZoneStateService zoneStateService;
    private final ObjectMapper objectMapper;
    private final OsrmCommandRunner commandRunner;
    private final OsrmMapFingerprint mapFingerprint;
    private Semaphore buildSlots = new Semaphore(MAX_CONCURRENT_BUILDS, true);

    /**
     * Initiates the async build pipeline for a zone.
     *
     * @param zoneId      zone identifier
     * @param polygon     polygon GeoJSON node
     * @param lineStrings lineStrings GeoJSON node (nullable)
     * @return completion future with the build result
     */
    @Async
    public CompletableFuture<BuildResult> buildZone(String zoneId, JsonNode polygon, JsonNode lineStrings) {
        Optional<Integer> ports = zoneStateService.findOsrmPort(zoneId);
        if (ports.isEmpty()) {
            log.error("Zone {}: not found in registry", zoneId);
            return CompletableFuture.completedFuture(
                    new BuildResult(zoneId, false, null, "zone not found in registry"));
        }

        int osrmPort = ports.get();
        ZoneProfile profile = zoneStateService.findProfile(zoneId).orElse(ZoneProfile.CAR);
        String zoneDirPath = "%s/%s".formatted(config.getZonesDir(), zoneId);
        Path zoneDir = Path.of(zoneDirPath);

        try {
            buildSlots.acquire();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return CompletableFuture.completedFuture(
                    new BuildResult(zoneId, false, osrmPort, "interrupted waiting for build slot"));
        }

        try {
            Files.createDirectories(zoneDir);
            InputFiles inputs = writeInputFiles(zoneDir, polygon, lineStrings);
            Path regionPbf = extractRegionPbf(zoneDir, inputs.polygonPath());
            buildCombinedPbf(zoneDir, regionPbf, inputs.lineStringsPath());
            buildOsrmMap(zoneDir, profile);
            mapFingerprint.write(zoneDir, mapFingerprint.pbfFingerprint(Path.of(config.getBasePbf())));
            cleanTempPBFs(zoneDir);
            zoneStateService.markZoneBuilt(zoneId);
            log.info("Zone {}: build complete", zoneId);
            return CompletableFuture.completedFuture(
                    new BuildResult(zoneId, true, osrmPort, null));
        } catch (Exception e) {
            log.error("Zone {}: build failed: {}", zoneId, e.getMessage(), e);
            zoneStateService.markZoneFailed(zoneId, e.getMessage());
            return CompletableFuture.completedFuture(
                    new BuildResult(zoneId, false, osrmPort, e.getMessage()));
        } finally {
            buildSlots.release();
        }
    }

    /**
     * Writes input GeoJSON files to the zone directory.
     *
     * @param zoneDir     target zone directory
     * @param polygon     polygon GeoJSON
     * @param lineStrings lineStrings GeoJSON (nullable)
     * @return record containing polygon and lineStrings paths
     * @throws IOException on file write failure
     */
    private InputFiles writeInputFiles(Path zoneDir, JsonNode polygon, JsonNode lineStrings) throws IOException {
        Path polygonPath = zoneDir.resolve(ZoneFiles.POLYGON_GEOJSON);
        Files.writeString(polygonPath, jsonString(polygon));
        Path lineStringsPath = null;
        if (lineStrings != null && !lineStrings.isNull()) {
            lineStringsPath = zoneDir.resolve(ZoneFiles.LINE_STRINGS_GEOJSON);
            Files.writeString(lineStringsPath, jsonString(lineStrings));
        }
        return new InputFiles(polygonPath, lineStringsPath);
    }

    /**
     * Runs osmium extract to produce region.osm.pbf from the polygon file.
     *
     * @param zoneDir     target zone directory
     * @param polygonPath path to polygon.geojson
     * @return path to the generated region.osm.pbf
     * @throws BuildException on subprocess failure
     * @throws IOException    on I/O failure
     */
    private Path extractRegionPbf(Path zoneDir, Path polygonPath) throws BuildException, IOException {
        Path regionPbf = zoneDir.resolve(FILE_REGION_PBF);
        runSubprocess(List.of(
                CMD_OSMIUM, FLAG_EXTRACT, FLAG_P, polygonPath.toString(),
                config.getBasePbf(), FLAG_O, regionPbf.toString(), FLAG_OVERWRITE
        ), null);
        return regionPbf;
    }

    /**
     * Produces combined.osm.pbf: if lineStrings present, merges region + custom_ways;
     * otherwise copies region as-is.
     *
     * @param zoneDir         target zone directory
     * @param regionPbf       path to region.osm.pbf
     * @param lineStringsPath path to lineStrings.geojson (null if absent)
     * @throws BuildException on subprocess failure
     * @throws IOException    on file copy failure
     */
    private void buildCombinedPbf(Path zoneDir, Path regionPbf, Path lineStringsPath) throws BuildException, IOException {
        Path customPbf = zoneDir.resolve(FILE_CUSTOM_WAYS_PBF);
        Path combinedPbf = zoneDir.resolve(FILE_COMBINED_PBF);
        if (lineStringsPath != null) {
            runSubprocess(List.of(
                    CMD_PYTHON, config.getReduceScript(),
                    regionPbf.toString(), lineStringsPath.toString(), customPbf.toString()
            ), zoneDir.toFile());
            runSubprocess(List.of(
                    CMD_OSMIUM, FLAG_MERGE, regionPbf.toString(), customPbf.toString(),
                    FLAG_O, combinedPbf.toString(), FLAG_OVERWRITE
            ), null);
        } else {
            Files.copy(regionPbf, combinedPbf, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Runs osrm-extract, osrm-partition, osrm-customize sequentially.
     *
     * @param zoneDir target zone directory
     * @param profile zone routing profile, resolving to the Lua script passed to {@code osrm-extract -p}
     * @throws BuildException on subprocess failure or timeout
     * @throws IOException    on I/O failure
     */
    private void buildOsrmMap(Path zoneDir, ZoneProfile profile) throws BuildException, IOException {
        Path mapOutput = zoneDir.resolve(FILE_OSRM_MAP_OUTPUT);
        String profileLua = commandRunner.profileLuaPath(profile);
        log.info("Zone {}: extracting map with profile '{}' ({})",
                zoneDir.getFileName(), profile.name(), profileLua);
        runSubprocess(List.of(
                BINARY_OS_RM_EXTRACT, FLAG_P, profileLua,
                FLAG_O, mapOutput.toString(), FILE_COMBINED_PBF
        ), zoneDir.toFile());
        runSubprocess(List.of(BINARY_OS_RM_PARTITION, FILE_OSRM_MAP_BASE), zoneDir.toFile());
        runSubprocess(List.of(BINARY_OS_RM_CUSTOMIZE, FILE_OSRM_MAP_BASE), zoneDir.toFile());
    }

    /**
     * Removes intermediate PBF files from the zone directory.
     *
     * @param zoneDir target zone directory
     */
    private void cleanTempPBFs(Path zoneDir) {
        for (String name : List.of(FILE_REGION_PBF, FILE_CUSTOM_WAYS_PBF, FILE_COMBINED_PBF)) {
            Path p = zoneDir.resolve(name);
            try {
                Files.deleteIfExists(p);
            } catch (Exception e) {
                log.warn("Failed to remove {}: {}", p, e.getMessage());
            }
        }
    }

    /**
     * Runs an external subprocess with the zone build timeout.
     *
     * <p>Protected so tests can stub the pipeline stages without executing real binaries.
     *
     * @param command command and arguments
     * @param cwd     working directory (or null for current directory)
     * @throws BuildException on timeout or non-zero exit code
     * @throws IOException    on I/O failure
     */
    protected void runSubprocess(List<String> command, File cwd) throws IOException {
        commandRunner.run(command, cwd, SUBPROCESS_TIMEOUT_SECONDS);
    }

    /**
     * Serializes a JsonNode to a compact JSON string.
     *
     * @param node JSON node to serialize
     * @return compact JSON string
     * @throws IllegalArgumentException on serialization failure
     */
    private String jsonString(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("invalid json", e);
        }
    }

    /**
     * Immutable record holding output paths from {@link #writeInputFiles}.
     */
    private record InputFiles(Path polygonPath, Path lineStringsPath) {
    }
}
