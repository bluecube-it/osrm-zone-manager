package it.bluecube.osrmzonemanager.runtime;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.zone.ZoneEvictionCandidate;
import it.bluecube.osrmzonemanager.zone.ZoneStateService;
import it.bluecube.osrmzonemanager.zone.ZoneStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvictorService {

    private final ZoneStateService zoneStateService;
    private final ProcessSupervisorService processSupervisor;
    private final OsrmZoneManagerConfig config;

    @Scheduled(fixedDelayString = "#{${osrm.zone-manager.evictor-interval-minutes:10} * 60000}")
    @Transactional
    public void evictExpiredZones() {
        List<ZoneEvictionCandidate> candidates = zoneStateService.findEvictionCandidates(List.of(
                ZoneStatus.ACTIVE.name(), ZoneStatus.DEGRADED.name()
        ));
        Instant now = Instant.now();
        long ttlDays = config.getZoneTtlDays();

        for (ZoneEvictionCandidate candidate : candidates) {
            Instant lastAccess = candidate.lastAccess();
            if (lastAccess == null) {
                continue;
            }
            if (lastAccess.plus(ttlDays, ChronoUnit.DAYS).isBefore(now)) {
                evict(candidate.zoneId());
            }
        }
    }

    private void evict(String zoneId) {
        log.info("Evictor: evicting zone {} (TTL expired)", zoneId);

        zoneStateService.markZoneEvicting(zoneId);

        try {
            processSupervisor.stopZone(zoneId);
        } catch (Exception e) {
            log.warn("Evictor: stop_zone failed for {}: {}", zoneId, e.getMessage());
        }

        Path zoneDir = Path.of(config.getZonesDir(), zoneId);
        if (Files.exists(zoneDir)) {
            try {
                deleteDirectory(zoneDir);
            } catch (Exception e) {
                log.warn("Evictor: failed to remove zone dir {}: {}", zoneDir, e.getMessage());
            }
        }

        zoneStateService.deleteById(zoneId);
        log.info("Evictor: zone {} evicted", zoneId);
    }

    private void deleteDirectory(Path path) throws Exception {
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
}
