package it.bluecube.osrmzonemanager.runtime;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.zone.ZoneStateService;
import it.bluecube.osrmzonemanager.zone.ZoneStatus;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Supervises the per-zone {@code osrm-routed} subprocess: start, health probing, restart and shutdown.
 *
 * <p>Process spawning and probing are delegated to {@link OsrmProcessLauncher}, which is shared with
 * the global whole-map instances.
 *
 * <p>VROOM is not supervised here: it is spawned per request by the in-process VROOM service, so
 * there is no long-lived VROOM process (and no per-zone VROOM port) to manage.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessSupervisorService {

    private static final int MAX_HEALTH_RETRIES = 3;
    private static final String FILE_OSRM_MAP_BASE = "map";

    private final OsrmZoneManagerConfig config;
    private final ZoneStateService zoneStateService;
    private final OsrmProcessLauncher launcher;
    private final OsrmMapFingerprint mapFingerprint;

    private final Map<String, ProcessInfo> registry = new ConcurrentHashMap<>();
    private final Map<String, Object> zoneLocks = new ConcurrentHashMap<>();

    public void startZone(String zoneId) {
        Object lock = zoneLocks.computeIfAbsent(zoneId, k -> new Object());
        synchronized (lock) {
            doStartZone(zoneId);
        }
    }

    private void doStartZone(String zoneId) {
        if (registry.containsKey(zoneId)) {
            log.info("Zone {}: already started, skipping", zoneId);
            return;
        }

        Optional<Integer> osrmPort = zoneStateService.findOsrmPort(zoneId);
        if (osrmPort.isEmpty()) {
            throw new IllegalStateException("zone " + zoneId + " not found");
        }
        if (osrmPort.get() == 0) {
            throw new IllegalStateException("zone " + zoneId + " has no port assigned");
        }

        Path zoneDir = Path.of(config.getZonesDir(), zoneId);
        String pbfFingerprint = mapFingerprint.pbfFingerprint(Path.of(config.getBasePbf()));
        if (!mapFingerprint.isUsable(zoneDir, pbfFingerprint)) {
            log.warn("Zone {}: map artifacts are incomplete or stale (different OSRM version / base PBF) — rebuild required",
                    zoneId);
            markFailed(zoneId, "map artifacts missing or stale — rebuild required");
            return;
        }

        ProcessInfo info = new ProcessInfo(zoneId, osrmPort.get());
        try {
            startOsrm(info);

            if (info.healthy) {
                zoneStateService.markZoneActive(zoneId, info.osrmPid);
                registry.put(zoneId, info);
                log.info("Zone {} started: osrm={}(pid={})", zoneId, info.osrmPort, info.osrmPid);
            } else {
                kill(info);
                markFailed(zoneId, "startup timeout");
            }
        } catch (Exception e) {
            log.error("Zone {}: startup failed: {}", zoneId, e.getMessage());
            kill(info);
            markFailed(zoneId, "startup error: " + e.getMessage());
        }
    }

    public void stopZone(String zoneId) {
        Object lock = zoneLocks.computeIfAbsent(zoneId, k -> new Object());
        synchronized (lock) {
            doStopZone(zoneId);
        }
    }

    private void doStopZone(String zoneId) {
        ProcessInfo info = registry.remove(zoneId);
        if (info == null) {
            log.warn("Zone {}: stop called but not in registry", zoneId);
            return;
        }
        kill(info);
        log.info("Zone {}: stopped (port {})", zoneId, info.osrmPort);
    }

    /**
     * Removes the per-zone lock from the registry. Called after zone is fully deleted.
     * Safe because deleteZone is the terminal state — no further startZone expected.
     */
    public void removeZoneLock(String zoneId) {
        zoneLocks.remove(zoneId);
    }

    @PreDestroy
    public void shutdown() {
        try {
            log.info("Shutdown: stopping all zones");
            stopAllZones();
        } catch (Exception e) {
            log.warn("Shutdown: error stopping zones: {}", e.getMessage());
        }
    }

    public void stopAllZones() {
        List<String> ids = List.copyOf(registry.keySet());
        for (String zoneId : ids) {
            stopZone(zoneId);
        }
    }

    public boolean isZoneRunning(String zoneId) {
        return registry.containsKey(zoneId);
    }

    public Set<String> allZoneIds() {
        return Set.copyOf(registry.keySet());
    }

    private void startOsrm(ProcessInfo info) {
        Path mapBase = Path.of(config.getZonesDir(), info.zoneId, FILE_OSRM_MAP_BASE);
        log.info("Zone {}: starting osrm-routed on port {} (map={})", info.zoneId, info.osrmPort, mapBase);
        try {
            info.osrm = launcher.launch(mapBase, info.osrmPort);
            info.osrmPid = info.osrm.pid();
        } catch (Exception e) {
            log.error("Zone {}: failed to start osrm-routed: {}", info.zoneId, e.getMessage());
            info.healthy = false;
            return;
        }

        boolean ok = launcher.waitRouteHealth(info.osrmPort, config.getOsrmStartTimeoutSeconds());
        info.healthy = ok;
        if (!ok) {
            log.error("Zone {}: osrm-routed timeout on port {}", info.zoneId, info.osrmPort);
            launcher.kill(info.osrm, "osrm");
            info.osrm = null;
        } else {
            log.info("Zone {}: osrm-routed healthy on port {} (pid={})",
                    info.zoneId, info.osrmPort, info.osrmPid);
        }
    }

    private void kill(ProcessInfo info) {
        if (info != null) {
            launcher.kill(info.osrm, "osrm(" + info.zoneId + ")");
        }
    }

    private void markFailed(String zoneId, String error) {
        zoneStateService.markZoneFailed(zoneId, error);
    }

    @Scheduled(fixedDelay = 30_000)
    public void healthCheck() {
        for (Map.Entry<String, ProcessInfo> entry : registry.entrySet()) {
            String zoneId = entry.getKey();
            ProcessInfo info = entry.getValue();
            Object lock = zoneLocks.computeIfAbsent(zoneId, k -> new Object());
            synchronized (lock) {
                try {
                    checkOne(zoneId, info);
                } catch (Exception e) {
                    log.warn("Zone {}: health check error: {}", zoneId, e.getMessage());
                }
            }
        }
    }

    private void checkOne(String zoneId, ProcessInfo info) {
        boolean osrmOk = launcher.ping(info.osrmPort);

        if (osrmOk) {
            if (!info.healthy) {
                info.healthy = true;
                info.retries = 0;
                markStatus(zoneId, ZoneStatus.ACTIVE, null);
                log.info("Zone {}: recovered to active", zoneId);
            }
            return;
        }

        info.retries++;
        if (info.retries > MAX_HEALTH_RETRIES) {
            info.healthy = false;
            markStatus(zoneId, ZoneStatus.DEGRADED, "unhealthy after " + info.retries + " retries");
            log.warn("Zone {}: marked degraded ({} retries)", zoneId, info.retries);
            return;
        }

        log.warn("Zone {}: unhealthy, restart attempt {}/{}", zoneId, info.retries, MAX_HEALTH_RETRIES);
        kill(info);
        info.osrm = null;
        info.healthy = false;

        startOsrm(info);
        if (info.healthy) {
            info.retries = 0;
            markStatus(zoneId, ZoneStatus.ACTIVE, null);
        }
    }

    private void markStatus(String zoneId, ZoneStatus status, String error) {
        zoneStateService.setStatusIfPresent(zoneId, status.name(), error);
    }
}
