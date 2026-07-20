package it.bluecube.osrmzonemanager.zone;

import it.bluecube.osrmzonemanager.HashUtils;
import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.builder.BuildPipelineService;
import it.bluecube.osrmzonemanager.builder.BuildResult;
import it.bluecube.osrmzonemanager.maps.MapsService;
import it.bluecube.osrmzonemanager.runtime.PortAllocatorService;
import it.bluecube.osrmzonemanager.runtime.ProcessSupervisorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/**
 * Business logic for zone lifecycle: creation, reuse, build orchestration,
 * status transitions, and deletion.
 *
 * <p>Throws domain exceptions — HTTP translation is the controller's responsibility.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZoneService {
    static final String ZONE_REUSE_MESSAGE = "zone already active with same content — reusing";
    // String-based for entity status comparison; mirrors ZoneStatus.isInProgress()
    private static final Set<String> IN_PROGRESS_STATUSES = Set.of(
            ZoneStatus.BUILDING.name(), ZoneStatus.BUILT.name(), ZoneStatus.STARTING.name()
    );
    // String-based for entity status comparison; mirrors ZoneStatus.isLive()
    private static final Set<String> LIVE_STATUSES = Set.of(
            ZoneStatus.ACTIVE.name(), ZoneStatus.DEGRADED.name()
    );
    // String-based for entity status comparison; mirrors ZoneStatus.isRunningOrStarting()
    private static final Set<String> RUNNING_OR_STARTING_STATUSES = Set.of(
            ZoneStatus.ACTIVE.name(), ZoneStatus.DEGRADED.name(), ZoneStatus.STARTING.name()
    );
    private static final int ZONE_ID_HEX_LENGTH = 12;
    private static final String ZONE_BUILD_STARTED_MESSAGE = "build started — poll GET /zones/{id} for status";
    private static final byte ZONE_ID_SEPARATOR = (byte) '|';

    private final ZoneStateService zoneStateService;
    private final PortAllocatorService portAllocator;
    private final BuildPipelineService buildPipelineService;
    private final ProcessSupervisorService processSupervisor;
    private final MapsService pbfDownloadService;
    private final OsrmZoneManagerConfig config;
    private final ObjectMapper objectMapper;
    private final ZoneMapper zoneMapper;
    private final Executor zoneManagerTaskExecutor;

    private final ConcurrentHashMap<String, CompletableFuture<BuildResult>> buildTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> startTasks = new ConcurrentHashMap<>();


    /**
     * Creates or reuses a zone for the given polygon/lineStrings input.
     *
     * @param polygon     required GeoJSON polygon
     * @param lineStrings optional GeoJSON lineStrings
     * @return the zone DTO (newly registered as BUILDING, or reused/active)
     */
    public ZoneDTO createOrReuseZone(JsonNode polygon, JsonNode lineStrings) {
        String basePbf = pbfDownloadService.ensureBasePbf();
        String baseMtime = String.valueOf(computeFileMtime(basePbf));
        String polygonHash = HashUtils.sha256(objectMapper.writeValueAsBytes(polygon));
        String lineStringsHash = lineStrings != null ? HashUtils.sha256(objectMapper.writeValueAsBytes(lineStrings)) : "";
        String zoneId = HashUtils.sha256(sha256InputBytes(polygon, lineStrings)).substring(0, ZONE_ID_HEX_LENGTH);

        ZoneEntity existing = zoneStateService.findById(zoneId).orElse(null);
        if (existing != null && existing.matchesContent(polygonHash, lineStringsHash, baseMtime)) {
            Optional<ZoneEntity> reuse = tryReuseExistingZone(existing, polygonHash);
            if (reuse.isPresent()) {
                return zoneMapper.toZoneDTO(reuse.get(), ZONE_REUSE_MESSAGE);
            }
        }

        int[] ports = reservePorts();
        ZoneEntity zone = zoneStateService.findById(zoneId)
                .filter(e -> e.matchesContent(polygonHash, lineStringsHash, baseMtime))
                .orElseGet(() -> buildZoneEntity(zoneId, polygon, lineStrings, polygonHash, lineStringsHash, baseMtime, new int[]{ports[0], ports[1]}));
        zone.setStatus(ZoneStatus.BUILDING.name());
        zone.setOsrmPort(ports[0]);
        zone.setVroomPort(ports[1]);
        zone.setError("");
        persistOrReleasePorts(zone, ports);

        launchBuild(zoneId, polygon, lineStrings);

        return zoneMapper.toZoneDTO(zone, ZONE_BUILD_STARTED_MESSAGE);
    }

    /**
     * @return all zones sorted by last access (descending)
     */
    public List<ZoneDTO> findAllZones() {
        return zoneStateService.findAll().stream()
                .sorted(Comparator.comparing(ZoneEntity::getLastAccess, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(zoneMapper::toDTO)
                .toList();
    }

    /**
     * @param zoneId zone identifier
     * @return the zone DTO
     * @throws ZoneNotFoundException if zone does not exist
     */
    public ZoneDTO findZone(String zoneId) {
        ZoneEntity entity = findEntity(zoneId);
        return zoneMapper.toDTO(entity);
    }

    /**
     * @param zoneId zone identifier
     * @return true if OSRM/Vroom processes are running for this zone
     */
    public boolean isZoneRunning(String zoneId) {
        return processSupervisor.isZoneRunning(zoneId);
    }

    /**
     * Touches a zone (updates lastAccess) — for proxy layer.
     */
    public void touch(String zoneId) {
        zoneStateService.touch(zoneId);
    }

    /**
     * Deletes a zone: cancels pending build/start futures, stops running processes,
     * removes the zone directory, and deletes the entity record.
     *
     * @param zoneId zone identifier
     * @throws ZoneNotFoundException if zone does not exist
     */
    @Transactional
    public void deleteZone(String zoneId) {
        ZoneEntity zone = findEntity(zoneId);
        String status = zone.getStatus();

        if (ZoneStatus.BUILDING.name().equals(status)) {
            cancelFuture(buildTasks.remove(zoneId));
        }
        if (ZoneStatus.BUILT.name().equals(status) || ZoneStatus.STARTING.name().equals(status)) {
            cancelFuture(startTasks.remove(zoneId));
        }
        if (RUNNING_OR_STARTING_STATUSES.contains(status)) {
            processSupervisor.stopZone(zoneId);
        }

        removeZoneDirectory(zoneId);
        zoneStateService.deleteById(zoneId);
        processSupervisor.removeZoneLock(zoneId);
        log.info("Zone {}: deleted", zoneId);
    }

    /**
     * Force-stops all zones, cancels pending futures, removes all zone directories,
     * and deletes all zone records from the registry.
     */
    @Transactional
    public void deleteAllZones() {
        processSupervisor.stopAllZones();

        List<ZoneEntity> zones = zoneStateService.findAll();
        for (ZoneEntity zone : zones) {
            String zoneId = zone.getZoneId();
            String status = zone.getStatus();

            if (ZoneStatus.BUILDING.name().equals(status)) {
                cancelFuture(buildTasks.remove(zoneId));
            }
            if (ZoneStatus.BUILT.name().equals(status) || ZoneStatus.STARTING.name().equals(status)) {
                cancelFuture(startTasks.remove(zoneId));
            }

            removeZoneDirectory(zoneId);
            processSupervisor.removeZoneLock(zoneId);
        }

        buildTasks.clear();
        startTasks.clear();
        zoneStateService.deleteAll();
        log.info("All zones cleaned: {} removed", zones.size());
    }

    /**
     * Attempts to reuse an existing zone whose content hash matches the incoming request.
     *
     * @param existing    the existing zone entity with matching content
     * @param polygonHash the incoming request's polygon hash
     * @return the zone entity if reuse succeeded, or empty if the caller should rebuild
     * @throws IllegalStateException if the zone is in-progress (conflict)
     */
    private Optional<ZoneEntity> tryReuseExistingZone(ZoneEntity existing, String polygonHash) {
        String zoneId = existing.getZoneId();
        String status = existing.getStatus();
        String zoneDirPath = config.getZonesDir() + "/" + zoneId + "/" + ZoneFiles.MAP_OSRM_PROPERTIES;

        if (LIVE_STATUSES.contains(status) && Files.exists(Paths.get(zoneDirPath))) {
            if (processSupervisor.isZoneRunning(zoneId)) {
                log.info("Zone reuse: matching hashes for polygon_hash={}",
                        polygonHash.substring(0, Math.min(polygonHash.length(), ZONE_ID_HEX_LENGTH)));
                return Optional.of(existing);
            }
            log.warn("Zone {}: record {} but processes not running, rebuilding", zoneId, status);
            processSupervisor.stopZone(zoneId);
            releasePortsQuietly(zoneId, existing.getOsrmPort(), existing.getVroomPort());
            zoneStateService.markZoneDegraded(zoneId, null);
        }

        if (IN_PROGRESS_STATUSES.contains(status)) {
            log.info("Zone in-progress: matching hashes, status={}", status);
            throw new ZoneInProgressException(zoneId, status);
        }

        return Optional.empty();
    }

    /**
     * @return int[2] with {osrmPort, vroomPort}
     * @throws IllegalStateException if ports cannot be allocated
     */
    private int[] reservePorts() {
        try {
            return portAllocator.reservePortPair();
        } catch (RuntimeException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * @param zoneId          the zone identifier
     * @param polygon         source GeoJSON polygon
     * @param lineStrings     source GeoJSON lineStrings (may be null)
     * @param polygonHash     SHA-256 of serialized polygon
     * @param lineStringsHash SHA-256 of serialized lineStrings (empty if null)
     * @param baseMtime       base PBF file modification time as string
     * @param ports           {osrmPort, vroomPort}
     * @return the constructed new zone entity in BUILDING state
     */
    private ZoneEntity buildZoneEntity(String zoneId, JsonNode polygon, JsonNode lineStrings,
                                       String polygonHash, String lineStringsHash, String baseMtime, int[] ports) {
        return ZoneEntity.builder()
                .zoneId(zoneId)
                .polygonHash(polygonHash)
                .lineStringsHash(lineStringsHash)
                .basePbfMtime(baseMtime)
                .status(ZoneStatus.BUILDING.name())
                .osrmPort(ports[0])
                .vroomPort(ports[1])
                .createdAt(Instant.now())
                .lastAccess(Instant.now())
                .polygonGeojson(objectMapper.writeValueAsString(polygon))
                .lineStringsGeojson(lineStrings != null ? objectMapper.writeValueAsString(lineStrings) : null)
                .build();
    }

    /**
     * Persists the zone entity; releases ports on failure.
     *
     * @param zone  the zone entity to persist
     * @param ports {osrmPort, vroomPort}
     */
    private void persistOrReleasePorts(ZoneEntity zone, int[] ports) {
        try {
            zoneStateService.save(zone);
        } catch (RuntimeException e) {
            log.warn("Zone {}: register failed: {}", zone.getZoneId(), e.getMessage());
            releasePortsQuietly(zone.getZoneId(), ports[0], ports[1]);
            throw e;
        }
    }

    /**
     * @param zoneId    zone identifier (for logging)
     * @param osrmPort  OSRM port to release
     * @param vroomPort Vroom port to release
     */
    private void releasePortsQuietly(String zoneId, int osrmPort, int vroomPort) {
        try {
            portAllocator.releasePort("osrm", osrmPort);
        } catch (RuntimeException e) {
            log.warn("Zone {}: release osrm port {} failed: {}", zoneId, osrmPort, e.getMessage());
        }
        try {
            portAllocator.releasePort("vroom", vroomPort);
        } catch (RuntimeException e) {
            log.warn("Zone {}: release vroom port {} failed: {}", zoneId, vroomPort, e.getMessage());
        }
    }

    /**
     * @param zoneId      the zone identifier
     * @param polygon     source GeoJSON polygon
     * @param lineStrings source GeoJSON lineStrings (may be null)
     */
    private void launchBuild(String zoneId, JsonNode polygon, JsonNode lineStrings) {
        CompletableFuture<BuildResult> buildFuture = buildPipelineService.buildZone(zoneId, polygon, lineStrings);
        buildTasks.put(zoneId, buildFuture);
        buildFuture.whenComplete((result, throwable) -> buildTasks.remove(zoneId));
        buildFuture.thenAcceptAsync(result -> startAfterBuild(zoneId, result), zoneManagerTaskExecutor);
    }

    // --- build/start lifecycle ---

    /**
     * @param zoneId the zone identifier
     * @param result the build result (null or failed → skip)
     */
    private void startAfterBuild(String zoneId, BuildResult result) {
        if (result == null || !result.ok()) {
            return;
        }
        if (!zoneStateService.findById(zoneId).isPresent()) {
            return;
        }
        zoneStateService.setStatusIfPresent(zoneId, ZoneStatus.STARTING.name(), null);

        CompletableFuture<Void> startFuture = CompletableFuture.runAsync(() -> {
            try {
                processSupervisor.startZone(zoneId);
            } catch (Exception e) {
                log.error("Zone {}: post-build start failed", zoneId, e);
                zoneStateService.markZoneFailed(zoneId, "post-build start: " + e.getMessage());
            }
        }, zoneManagerTaskExecutor);
        startTasks.put(zoneId, startFuture);
        startFuture.whenComplete((v, t) -> startTasks.remove(zoneId));
    }

    // --- internal entity access (package-private, same-package only) ---

    /**
     * @param zoneId zone identifier
     * @return the zone entity
     * @throws ZoneNotFoundException if zone does not exist
     */
    ZoneEntity findEntity(String zoneId) {
        return zoneStateService.findById(zoneId).orElseThrow(() -> new ZoneNotFoundException(zoneId));
    }

    // --- crypto / json helpers ---

    /**
     * @param polygon     source GeoJSON polygon
     * @param lineStrings source GeoJSON lineStrings (may be null)
     * @return combined bytes for zone ID generation
     */
    private byte[] sha256InputBytes(JsonNode polygon, JsonNode lineStrings) {
        byte[] polygonBytes = objectMapper.writeValueAsBytes(polygon);
        if (lineStrings == null || lineStrings.isNull()) {
            return polygonBytes;
        }
        byte[] lineStringsBytes = objectMapper.writeValueAsBytes(lineStrings);
        byte[] source = new byte[polygonBytes.length + 1 + lineStringsBytes.length];
        System.arraycopy(polygonBytes, 0, source, 0, polygonBytes.length);
        source[polygonBytes.length] = ZONE_ID_SEPARATOR;
        System.arraycopy(lineStringsBytes, 0, source, polygonBytes.length + 1, lineStringsBytes.length);
        return source;
    }

    /**
     * @param path file path
     * @return modification time in millis, or 0 on error
     */
    private long computeFileMtime(String path) {
        try {
            return Files.getLastModifiedTime(Path.of(path)).toMillis();
        } catch (Exception e) {
            log.debug("Could not read mtime for {}: {}", path, e.getMessage());
            return 0;
        }
    }

    /**
     * @param future future to cancel (no-op if null or already done)
     */
    private void cancelFuture(CompletableFuture<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    /**
     * @param zoneId zone identifier (for logging)
     */
    private void removeZoneDirectory(String zoneId) {
        Path zoneDir = Path.of(config.getZonesDir(), zoneId);
        if (!Files.exists(zoneDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(zoneDir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception e) {
                            log.warn("Failed to delete {}: {}", p, e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.warn("Zone {}: failed to remove zone dir: {}", zoneId, e.getMessage());
        }
    }
}
