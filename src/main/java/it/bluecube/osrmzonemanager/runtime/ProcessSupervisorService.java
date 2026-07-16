package it.bluecube.osrmzonemanager.runtime;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.zone.ZonePorts;
import it.bluecube.osrmzonemanager.zone.ZoneStateService;
import it.bluecube.osrmzonemanager.zone.ZoneStatus;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessSupervisorService {

    private static final int OSRM_HEALTH_TIMEOUT_SECONDS = 120;
    private static final int VROOM_HEALTH_TIMEOUT_SECONDS = 60;
    private static final int PING_TIMEOUT_MS = 5_000;
    private static final int MAX_HEALTH_RETRIES = 3;
    private static final String FILE_OSRM_MAP_BASE = "map";
    private static final String FILE_VROOM_DIR = "vroom-express";
    private static final String FILE_SRC = "src";
    private static final String FILE_INDEX_JS = "index.js";
    private static final String BINARY_OS_RM_ROUTED = "osrm-routed";
    private static final String FLAG_ALGORITHM = "--algorithm";
    private static final String ALGORITHM_MLD = "mld";
    private static final String FLAG_IP = "--ip";
    private static final String FLAG_PORT = "--port";
    private static final String FLAG_MMAP = "--mmap";
    private static final String LOCALHOST = "127.0.0.1";
    private static final String HTTP_SCHEME = "http://";
    private static final String ROUTE_PATH_DRIVING = "/route/v1/driving/0,0;0,0";
    private static final String HEALTH_PATH = "/health";
    private static final String BINARY_NODE = "node";
    private static final String ENV_VAR_NODE_PATH = "NODE_PATH";

    private final OsrmZoneManagerConfig config;
    private final ZoneStateService zoneStateService;

    private final Map<String, ProcessInfo> registry = new ConcurrentHashMap<>();
    private final Map<String, Object> zoneLocks = new ConcurrentHashMap<>();
    private final RestClient pingClient = RestClient.builder()
            .requestFactory(clientHttpRequestFactory())
            .build();

    private static SimpleClientHttpRequestFactory clientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(PING_TIMEOUT_MS);
        factory.setReadTimeout(PING_TIMEOUT_MS);
        return factory;
    }

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

        Optional<ZonePorts> ports = zoneStateService.findPorts(zoneId);
        if (ports.isEmpty()) {
            throw new IllegalStateException("zone " + zoneId + " not found");
        }
        int osrmPort = ports.get().osrmPort();
        int vroomPort = ports.get().vroomPort();
        if (osrmPort == 0 || vroomPort == 0) {
            throw new IllegalStateException("zone " + zoneId + " has no ports assigned");
        }

        ProcessInfo info = new ProcessInfo(zoneId, osrmPort, vroomPort);
        try {
            startOsrm(info);
            if (info.healthy) {
                startVroom(info);
            }

            if (info.healthy) {
                zoneStateService.markZoneActive(zoneId, info.osrmPid, info.vroomPid);
                registry.put(zoneId, info);
                log.info("Zone {} started: osrm={}(pid={}) vroom={}(pid={})",
                        zoneId, osrmPort, info.osrmPid, vroomPort, info.vroomPid);
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
        log.info("Zone {}: stopped (ports {}/{})", zoneId, info.osrmPort, info.vroomPort);
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
        String mapBase = config.getZonesDir() + "/" + info.zoneId + "/" + FILE_OSRM_MAP_BASE;
        List<String> command = new java.util.ArrayList<>(List.of(
                BINARY_OS_RM_ROUTED, FLAG_ALGORITHM, ALGORITHM_MLD,
                FLAG_IP, LOCALHOST, FLAG_PORT, String.valueOf(info.osrmPort), mapBase
        ));
        if (config.isOsrmMmap()) {
            command.add(FLAG_MMAP);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        try {
            info.osrm = pb.start();
            info.osrmPid = info.osrm.pid();
        } catch (Exception e) {
            log.error("Zone {}: failed to start osrm-routed: {}", info.zoneId, e.getMessage());
            info.healthy = false;
            return;
        }

        boolean ok = waitHealth(
                HTTP_SCHEME + LOCALHOST + ":" + info.osrmPort + ROUTE_PATH_DRIVING,
                OSRM_HEALTH_TIMEOUT_SECONDS
        );
        info.healthy = ok;
        if (!ok) {
            log.error("Zone {}: osrm-routed timeout on port {}", info.zoneId, info.osrmPort);
            killSingle(info.osrm, "osrm");
            info.osrm = null;
        } else {
            log.info("Zone {}: osrm-routed healthy on port {} (pid={})",
                    info.zoneId, info.osrmPort, info.osrmPid);
        }
    }

    private void startVroom(ProcessInfo info) {
        Path vroomDir = Path.of(config.getZonesDir(), info.zoneId, FILE_VROOM_DIR);
        if (!Files.exists(vroomDir)) {
            log.error("Zone {}: vroom-express dir missing at {}", info.zoneId, vroomDir);
            info.healthy = false;
            return;
        }

        ProcessBuilder pb = new ProcessBuilder(
                BINARY_NODE, Path.of(config.getVroomExpressDir(), FILE_SRC, FILE_INDEX_JS).toString()
        );
        pb.directory(vroomDir.toFile());
        pb.inheritIO();
        Map<String, String> env = pb.environment();
        env.put(ENV_VAR_NODE_PATH, config.getVroomExpressDir() + "/node_modules");

        try {
            info.vroom = pb.start();
            info.vroomPid = info.vroom.pid();
        } catch (Exception e) {
            log.error("Zone {}: failed to start vroom-express: {}", info.zoneId, e.getMessage());
            info.healthy = false;
            return;
        }

        boolean ok = waitHealth(
                HTTP_SCHEME + LOCALHOST + ":" + info.vroomPort + HEALTH_PATH,
                VROOM_HEALTH_TIMEOUT_SECONDS
        );
        info.healthy = info.healthy && ok;
        if (!ok) {
            log.error("Zone {}: vroom-express timeout on port {}", info.zoneId, info.vroomPort);
            killSingle(info.vroom, "vroom");
            info.vroom = null;
        } else {
            log.info("Zone {}: vroom-express healthy on port {} (pid={})",
                    info.zoneId, info.vroomPort, info.vroomPid);
        }
    }

    private boolean waitHealth(String url, int timeoutSeconds) {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        while (Instant.now().isBefore(deadline)) {
            if (ping(url)) {
                return true;
            }
            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean ping(String url) {
        try {
            ResponseEntity<Void> response = pingClient.get()
                    .uri(url)
                    .retrieve()
                    .onStatus(status -> true, (req, resp) -> {
                    })
                    .toBodilessEntity();
            int status = response.getStatusCode().value();
            return status < 500 || status == 400;
        } catch (ResourceAccessException e) {
            log.debug("Ping failed for {}: {}", url, e.getMessage());
            return false;
        }
    }

    private void kill(ProcessInfo info) {
        if (info != null) {
            killSingle(info.osrm, "osrm(" + info.zoneId + ")");
            killSingle(info.vroom, "vroom(" + info.zoneId + ")");
        }
    }

    private void killSingle(Process process, String name) {
        if (process == null || !process.isAlive()) {
            return;
        }
        try {
            killDescendants(process, name);
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        log.debug("Killed {} (pid={})", name, process.pid());
    }

    private void killDescendants(Process process, String name) {
        try {
            process.descendants().forEach(ph -> {
                try {
                    ph.destroy();
                    try {
                        ph.onExit().get(1, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.debug("Descendant {} of {} did not terminate within 1s, forcibly killing", ph.pid(), name);
                        ph.destroyForcibly();
                    }
                } catch (Exception e) {
                    log.debug("Failed to kill descendant of {} (pid={}): {}", name, ph.pid(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.debug("Failed to enumerate descendants of {} (pid={}): {}", name, process.pid(), e.getMessage());
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
        String osrmUrl = "http://" + LOCALHOST + ":" + info.osrmPort + ROUTE_PATH_DRIVING;
        String vroomUrl = "http://" + LOCALHOST + ":" + info.vroomPort + HEALTH_PATH;
        boolean osrmOk = ping(osrmUrl);
        boolean vroomOk = ping(vroomUrl);

        if (osrmOk && vroomOk) {
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
        info.vroom = null;
        info.healthy = false;

        startOsrm(info);
        if (info.healthy) {
            startVroom(info);
        }
        if (info.healthy) {
            info.retries = 0;
            markStatus(zoneId, ZoneStatus.ACTIVE, null);
        }
    }

    private void markStatus(String zoneId, ZoneStatus status) {
        markStatus(zoneId, status, null);
    }

    private void markStatus(String zoneId, ZoneStatus status, String error) {
        zoneStateService.setStatusIfPresent(zoneId, status.name(), error);
    }
}
