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
 * On-boot zone recovery: scans the persistent zone registry and brings every zone back online — by
 * starting its OSRM process when the preprocessed map is still loadable, or by rebuilding it.
 *
 * <p>A map is only considered loadable when it is complete <em>and</em> was produced by the OSRM
 * version installed in the running image ({@link OsrmMapFingerprint}); OSRM refuses to load a graph
 * built by another release, which otherwise leaves the zone permanently {@code FAILED}. Zones in
 * {@code FAILED} are recovered too (self-heal) rather than skipped, since the failure may simply have
 * been a stale map or a resource-starved build.
 *
 * <p>Recovery runs asynchronously on {@code zoneManagerTaskExecutor} so that PBF warm-up and
 * per-zone rebuilds do not block application startup.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BootRecoveryService implements ApplicationRunner {

    private final MapsService pbfDownloadService;
    private final ZoneStateService zoneStateService;
    private final BuildPipelineService buildPipelineService;
    private final ProcessSupervisorService processSupervisor;
    private final OsrmMapFingerprint mapFingerprint;
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
        Path basePbf;
        try {
            basePbf = Path.of(pbfDownloadService.ensureBasePbf());
        } catch (Exception e) {
            log.error("Boot: base PBF check failed: {}", e.getMessage());
            return;
        }
        String pbfFingerprint = mapFingerprint.pbfFingerprint(basePbf);

        List<ZoneRecoveryDTO> zones = zoneStateService.findAllRecoveryData();
        if (zones.isEmpty()) {
            log.info("Boot recovery: no zones in registry");
            return;
        }
        log.info("Boot recovery: found {} zone(s)", zones.size());

        for (ZoneRecoveryDTO zone : zones) {
            try {
                recoverZone(zone, pbfFingerprint);
            } catch (Exception e) {
                log.error("Boot recovery: zone {} failed: {}", zone.zoneId(), e.getMessage());
            }
        }
    }

    /**
     * Starts the zone when its map is still loadable, otherwise rebuilds it from the stored polygon.
     *
     * <p>Unknown statuses are skipped (nothing sensible can be decided for them), every other status is
     * recovered — including {@code FAILED}, which used to be terminal and left zombie zones behind.
     *
     * @param zone           the recovery data for the zone
     * @param pbfFingerprint identity of the base PBF currently mounted
     */
    private void recoverZone(ZoneRecoveryDTO zone, String pbfFingerprint) {
        String zoneId = zone.zoneId();
        ZoneStatus status = ZoneStatus.parseSafe(zone.status());
        if (status == null) {
            log.warn("Boot recovery: zone {} status='{}' — skipping", zoneId, zone.status());
            return;
        }

        Path zoneDir = Path.of(config.getZonesDir(), zoneId);
        // for a live zone the on-disk polygon must still match the registry, otherwise the map no
        // longer describes the requested area and has to be rebuilt
        boolean polygonOk = !status.isLive() || polygonHashMatches(zone);
        if (mapFingerprint.isUsable(zoneDir, pbfFingerprint) && polygonOk) {
            log.info("Boot recovery: zone {} status={} map is loadable — starting", zoneId, status);
            processSupervisor.startZone(zoneId);
            return;
        }

        log.info("Boot recovery: zone {} status={} map is missing or stale — rebuilding", zoneId, status);
        rebuild(zone);
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
     * {@code false} if the file is unreadable or the hash differs
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
