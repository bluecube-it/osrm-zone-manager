package it.bluecube.osrmzonemanager.builder;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.runtime.PortAllocatorService;
import it.bluecube.osrmzonemanager.zone.ZoneFiles;
import it.bluecube.osrmzonemanager.zone.ZonePorts;
import it.bluecube.osrmzonemanager.zone.ZoneStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates the build pipeline for a single OSRM zone.
 * Pipeline stages: osmium extract → (reduce.py merge) → osrm-extract → osrm-partition → osrm-customize → vroom-express prep.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuildPipelineService {

    private static final int SUBPROCESS_TIMEOUT_SECONDS = 600;
    private static final int MAX_CONCURRENT_BUILDS = 3;
    private static final int MAX_OUTPUT_LINES = 500;
    private static final String FILE_REGION_PBF = "region.osm.pbf";
    private static final String FILE_CUSTOM_WAYS_PBF = "custom_ways.pbf";
    private static final String FILE_COMBINED_PBF = "combined.osm.pbf";
    private static final String FILE_OSRM_MAP_BASE = "map.osrm";
    private static final String FILE_OSRM_MAP_OUTPUT = "map";
    private static final String FILE_VROOM_DIR = "vroom-express";
    private static final String BINARY_OS_RM_EXTRACT = "osrm-extract";
    private static final String BINARY_OS_RM_PARTITION = "osrm-partition";
    private static final String BINARY_OS_RM_CUSTOMIZE = "osrm-customize";
    private static final String PLACEHOLDER_OS_RM_PORT = "{{OSRM_PORT}}";
    private static final String PLACEHOLDER_VROOM_PORT = "{{VROOM_PORT}}";
    private static final String FILE_HEALTHCHECKS = "healthchecks";
    private static final String FILE_HEALTHCHECKS_MATRIX = "vroom_custom_matrix.json";
    private static final String FILE_CONFIG_YML = "config.yml";
    private static final String TEMPLATE_PATH = "config/vroom-config.template.yml";
    private static final String CMD_OSMIUM = "osmium";
    private static final String CMD_PYTHON = "python3";
    private static final String SVC_OSRM = "osrm";
    private static final String SVC_VROOM = "vroom";
    private static final String FLAG_EXTRACT = "extract";
    private static final String FLAG_MERGE = "merge";
    private static final String FLAG_P = "-p";
    private static final String FLAG_O = "-o";
    private static final String FLAG_OVERWRITE = "--overwrite";
    private Semaphore buildSlots = new Semaphore(MAX_CONCURRENT_BUILDS, true);

    private final OsrmZoneManagerConfig config;
    private final ZoneStateService zoneStateService;
    private final PortAllocatorService portAllocator;
    private final ObjectMapper objectMapper;

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
        Optional<ZonePorts> ports = zoneStateService.findPorts(zoneId);
        if (ports.isEmpty()) {
            log.error("Zone {}: not found in registry", zoneId);
            return CompletableFuture.completedFuture(
                    new BuildResult(zoneId, false, null, null, "zone not found in registry"));
        }

        int osrmPort = ports.get().osrmPort();
        int vroomPort = ports.get().vroomPort();
        String zoneDirPath = "%s/%s".formatted(config.getZonesDir(), zoneId);
        Path zoneDir = Path.of(zoneDirPath);

        try {
            buildSlots.acquire();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return CompletableFuture.completedFuture(
                    new BuildResult(zoneId, false, osrmPort, vroomPort, "interrupted waiting for build slot"));
        }

        try {
            Files.createDirectories(zoneDir);
            InputFiles inputs = writeInputFiles(zoneDir, polygon, lineStrings);
            Path regionPbf = extractRegionPbf(zoneDir, inputs.polygonPath());
            buildCombinedPbf(zoneDir, regionPbf, inputs.lineStringsPath());
            buildOsrmMap(zoneDir);
            cleanTempPBFs(zoneDir);
            prepareVroomExpressDir(zoneDir, osrmPort, vroomPort);
            zoneStateService.markZoneBuilt(zoneId);
            log.info("Zone {}: build complete", zoneId);
            return CompletableFuture.completedFuture(
                    new BuildResult(zoneId, true, osrmPort, vroomPort, null));
        } catch (Exception e) {
            log.error("Zone {}: build failed: {}", zoneId, e.getMessage(), e);
            zoneStateService.markZoneFailed(zoneId, e.getMessage());
            portAllocator.releasePort(SVC_OSRM, osrmPort);
            portAllocator.releasePort(SVC_VROOM, vroomPort);
            return CompletableFuture.completedFuture(
                    new BuildResult(zoneId, false, osrmPort, vroomPort, e.getMessage()));
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
                    config.getBasePbf(), lineStringsPath.toString(), customPbf.toString()
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
     * @throws BuildException on subprocess failure or timeout
     * @throws IOException    on I/O failure
     */
    private void buildOsrmMap(Path zoneDir) throws BuildException, IOException {
        Path mapOutput = zoneDir.resolve(FILE_OSRM_MAP_OUTPUT);
        runSubprocess(List.of(
                BINARY_OS_RM_EXTRACT, FLAG_P, config.getCarLua(),
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
     * Prepares the vroom-express directory with healthchecks matrix and config.yml.
     *
     * @param zoneDir   target zone directory
     * @param osrmPort  OSRM port
     * @param vroomPort VROOM port
     * @throws IOException on file operation failure
     */
    private void prepareVroomExpressDir(Path zoneDir, int osrmPort, int vroomPort) throws IOException {
        Path vroomDir = zoneDir.resolve(FILE_VROOM_DIR);
        if (Files.exists(vroomDir)) {
            deleteDirectory(vroomDir);
        }
        Files.createDirectories(vroomDir.resolve(FILE_HEALTHCHECKS));

        Path sourceHc = Path.of(config.getVroomExpressDir(), FILE_HEALTHCHECKS, FILE_HEALTHCHECKS_MATRIX);
        if (Files.exists(sourceHc)) {
            Files.copy(sourceHc, vroomDir.resolve(FILE_HEALTHCHECKS).resolve(FILE_HEALTHCHECKS_MATRIX));
        }

        ClassPathResource template = new ClassPathResource(TEMPLATE_PATH);
        String content = new String(template.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        content = content.replace(PLACEHOLDER_OS_RM_PORT, String.valueOf(osrmPort))
                .replace(PLACEHOLDER_VROOM_PORT, String.valueOf(vroomPort));
        Files.writeString(vroomDir.resolve(FILE_CONFIG_YML), content);
    }

    /**
     * Runs an external subprocess with a timeout.
     *
     * @param command command and arguments
     * @param cwd     working directory (or null for current directory)
     * @throws BuildException on timeout or non-zero exit code
     * @throws IOException    on I/O failure
     */
    protected void runSubprocess(List<String> command, File cwd) throws IOException {
        log.info("Starting subprocess: {}", String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        if (cwd != null) {
            pb.directory(cwd);
        }
        pb.redirectErrorStream(true);
        Process process = pb.start();

        Deque<String> output = new ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.addLast(line);
                if (output.size() > MAX_OUTPUT_LINES) {
                    output.removeFirst();
                }
                log.debug("{}", line);
            }
        }

        boolean finished;
        try {
            finished = process.waitFor(SUBPROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BuildException("subprocess wait interrupted", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new BuildException("subprocess timed out after "
                    + SUBPROCESS_TIMEOUT_SECONDS + "s: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            List<String> tailList = new ArrayList<>(output);
            String tail = String.join("\n", tailList.subList(
                    Math.max(0, tailList.size() - 20), tailList.size()));
            throw new BuildException("subprocess failed (rc=" + process.exitValue()
                    + "): " + String.join(" ", command) + " - " + tail);
        }
        if (!output.isEmpty()) {
            log.info("{}: {}", String.join(" ", command), output.getLast());
        }
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
     * Recursively deletes all files and the directory itself.
     *
     * @param path root of the directory tree to delete
     * @throws IOException on file deletion failure
     */
    private void deleteDirectory(Path path) throws IOException {
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception e) {
                            log.warn("Failed to delete {}: {}", p, e.getMessage());
                        }
                    });
        }
    }

    /**
     * Immutable record holding output paths from {@link #writeInputFiles}.
     */
    private record InputFiles(Path polygonPath, Path lineStringsPath) {
    }
}
