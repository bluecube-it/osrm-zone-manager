package it.bluecube.osrmzonemanager.runtime;

import it.bluecube.osrmzonemanager.HashUtils;
import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.builder.BuildPipelineService;
import it.bluecube.osrmzonemanager.maps.MapsService;
import it.bluecube.osrmzonemanager.zone.ZoneFiles;
import it.bluecube.osrmzonemanager.zone.ZoneRecoveryDTO;
import it.bluecube.osrmzonemanager.zone.ZoneStateService;
import it.bluecube.osrmzonemanager.zone.ZoneStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * On-boot zone recovery: scans the persistent zone registry and brings every
 * zone back online — either by restarting OSRM/VROOM processes or by scheduling
 * a full rebuild when artifacts are missing or stale.
 *
 * <p>Recovery runs asynchronously on {@code zoneManagerTaskExecutor} so that
 * PBF warm-up and per-zone rebuilds do not block application startup.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BootRecoveryService implements ApplicationRunner {

    private final MapsService pbfDownloadService;
    private final ZoneStateService zoneStateService;
    private final BuildPipelineService buildPipelineService;
    private final ProcessSupervisorService processSupervisor;
    private final OsrmZoneManagerConfig config;
    private final ObjectMapper objectMapper;
    private final Executor zoneManagerTaskExecutor;

    /**
     * Kicks off recovery asynchronously on {@code zoneManagerTaskExecutor}.
     *
     * @param args Spring Boot startup arguments (unused)
     */
    @Override
    public void run(@NonNull ApplicationArguments args) {
        CompletableFuture.runAsync(this::recover, zoneManagerTaskExecutor);
    }

    /**
     * Ensures the base PBF is available, then iterates over every registered zone
     * and dispatches it to the appropriate recovery path.
     * Exceptions per zone are caught and logged so one failure does not stop the loop.
     */
    private void recover() {
        log.info("Boot: osrm-zone-manager starting recovery");
        try {
            pbfDownloadService.ensureBasePbf();
        } catch (Exception e) {
            log.error("Boot: base PBF check failed: {}", e.getMessage());
            return;
        }

        List<ZoneRecoveryDTO> zones = zoneStateService.findAllRecoveryData();
        if (zones.isEmpty()) {
            log.info("Boot recovery: no zones in registry");
            return;
        }
        log.info("Boot recovery: found {} zone(s)", zones.size());

        for (ZoneRecoveryDTO zone : zones) {
            try {
                recoverZone(zone);
            } catch (Exception e) {
                log.error("Boot recovery: zone {} failed: {}", zone.zoneId(), e.getMessage());
            }
        }
    }

    /**
     * Dispatches a zone to the correct recovery path based on its persisted status.
     * Unknown or non-actionable statuses (FAILED, EVICTING) are skipped with a warning.
     *
     * @param zone the recovery data for the zone
     */
    private void recoverZone(ZoneRecoveryDTO zone) {
        String zoneId = zone.zoneId();
        ZoneStatus status = ZoneStatus.parseSafe(zone.status());
        if (status == null) {
            log.warn("Boot recovery: zone {} status='{}' — skipping", zoneId, zone.status());
            return;
        }

        switch (status) {
            case BUILDING -> rebuild(zone);
            case ACTIVE, DEGRADED -> recoverLiveZone(zone);
            case BUILT, STARTING -> recoverPendingZone(zone);
            default -> log.warn("Boot recovery: zone {} status='{}' — skipping", zoneId, status);
        }
    }

    /**
     * Attempts to restart a live (ACTIVE/DEGRADED) zone whose on-disk polygon
     * still matches the registry hash. Falls back to a full rebuild if the
     * map file is missing or the polygon changed.
     *
     * @param zone the recovery data for the zone
     */
    private void recoverLiveZone(ZoneRecoveryDTO zone) {
        String zoneId = zone.zoneId();
        if (mapFileExists(zoneId) && polygonHashMatches(zone)) {
            processSupervisor.startZone(zoneId);
        } else {
            rebuild(zone);
        }
    }

    /**
     * Attempts to start a zone that was BUILT but never started (or was STARTING).
     * Falls back to a full rebuild if the map file is missing.
     *
     * @param zone the recovery data for the zone
     */
    private void recoverPendingZone(ZoneRecoveryDTO zone) {
        String zoneId = zone.zoneId();
        if (mapFileExists(zoneId)) {
            processSupervisor.startZone(zoneId);
        } else {
            rebuild(zone);
        }
    }

    /**
     * Schedules a full rebuild of the zone via the build pipeline, then starts
     * the OSRM/VROOM processes once the build completes.
     * If the zone has no polygon in the registry, marks it as FAILED instead.
     *
     * @param zone the recovery data for the zone
     */
    private void rebuild(ZoneRecoveryDTO zone) {
        String zoneId = zone.zoneId();
        JsonNode polygon = parseJson(zone.polygonGeojson());
        if (polygon == null) {
            log.warn("Boot recovery: zone {}: no polygon in registry — marking failed", zoneId);
            zoneStateService.setRecoveryFailed(zoneId, "polygon not in registry, cannot rebuild");
            return;
        }
        JsonNode lineStrings = parseJson(zone.lineStringsGeojson());
        zoneStateService.markZoneBuilding(zoneId);

        buildPipelineService.buildZone(zoneId, polygon, lineStrings)
                .thenAcceptAsync(result -> {
                    if (result != null && result.ok()) {
                        processSupervisor.startZone(zoneId);
                        log.info("Boot recovery: zone {} rebuilt and started", zoneId);
                    } else {
                        log.error("Boot recovery: zone {} rebuild failed: {}", zoneId, result != null ? result.error() : "unknown");
                    }
                }, zoneManagerTaskExecutor);
    }

    /**
     * Checks whether the on-disk polygon file still matches the hash stored in the registry.
     *
     * @param zone the recovery data for the zone
     * @return {@code true} if the hashes match or no polygon file exists on disk;
     *         {@code false} if the file is unreadable or the hash differs
     */
    private boolean polygonHashMatches(ZoneRecoveryDTO zone) {
        Path polygonFile = Path.of(config.getZonesDir(), zone.zoneId(), ZoneFiles.POLYGON_GEOJSON);
        if (!Files.exists(polygonFile)) {
            return true;
        }
        try {
            byte[] content = Files.readAllBytes(polygonFile);
            String actualHash = HashUtils.sha256(content);
            return actualHash.equals(zone.polygonHash());
        } catch (Exception e) {
            log.warn("Boot recovery: hash check failed for zone {}: {}", zone.zoneId(), e.getMessage());
            return false;
        }
    }

    /**
     * Checks whether the OSRM map file exists for the given zone.
     *
     * @param zoneId the zone identifier
     * @return {@code true} if the map file is present on disk
     */
    private boolean mapFileExists(String zoneId) {
        Path mapFile = Path.of(config.getZonesDir(), zoneId, ZoneFiles.MAP_OSRM_PROPERTIES);
        return Files.exists(mapFile);
    }

    /**
     * Parses a JSON string into a {@link JsonNode}.
     *
     * @param json the JSON string to parse, may be {@code null} or blank
     * @return the parsed node, or {@code null} if the input is blank or parsing fails
     */
    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("Failed to parse stored geojson: {}", e.getMessage());
            return null;
        }
    }
}
