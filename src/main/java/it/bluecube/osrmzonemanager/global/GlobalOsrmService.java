package it.bluecube.osrmzonemanager.global;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.builder.BuildException;
import it.bluecube.osrmzonemanager.builder.BuildSerializer;
import it.bluecube.osrmzonemanager.builder.OsrmCommandRunner;
import it.bluecube.osrmzonemanager.maps.MapsService;
import it.bluecube.osrmzonemanager.runtime.OsrmMapFingerprint;
import it.bluecube.osrmzonemanager.runtime.OsrmProcessLauncher;
import it.bluecube.osrmzonemanager.runtime.PortAllocatorService;
import it.bluecube.osrmzonemanager.zone.ZoneProfile;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Boots one global {@code osrm-routed} instance per {@link ZoneProfile} over the <em>whole</em>
 * base PBF, so that clients can route on the full map without creating a zone.
 *
 * <p>Per profile the preprocessed graph lives in {@code <data-dir>/global/<profile>} and is rebuilt
 * from {@link OsrmZoneManagerConfig#getBasePbf()} only when missing or when the base PBF changed
 * (tracked by a sidecar mtime marker). Build then start runs asynchronously on
 * {@code zoneManagerTaskExecutor} so application startup — and readiness — is not blocked by a
 * whole-map {@code osrm-extract}, which can take a long time.
 *
 * <p>Requests are served by {@code /osrm/{profile}/**}; until a profile reaches
 * {@link GlobalOsrmStatus#READY} (or {@link GlobalOsrmStatus#DEGRADED}) those requests get HTTP 503.
 *
 * <p>Whole-map preprocessing is memory-hungry — the whole-Italy extract peaks around 15 GB — so every
 * profile build takes the single, shared {@link BuildSerializer} slot and never runs next to a zone
 * build.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalOsrmService implements ApplicationRunner {

    private static final int OSRM_HEALTH_TIMEOUT_SECONDS = 600;
    private static final int MAX_HEALTH_RETRIES = 3;
    private static final String FILE_OSRM_MAP_BASE = "map";
    private static final String FLAG_EXTRACT = "-p";
    private static final String FLAG_OUTPUT = "-o";
    private static final String BINARY_OSRM_EXTRACT = "osrm-extract";
    private static final String BINARY_OSRM_PARTITION = "osrm-partition";
    private static final String BINARY_OSRM_CUSTOMIZE = "osrm-customize";
    private static final String MAP_OSRM = FILE_OSRM_MAP_BASE + ".osrm";

    private final OsrmZoneManagerConfig config;
    private final MapsService mapsService;
    private final OsrmCommandRunner commandRunner;
    private final BuildSerializer buildSerializer;
    private final OsrmProcessLauncher launcher;
    private final OsrmMapFingerprint mapFingerprint;
    private final PortAllocatorService portAllocator;
    private final GlobalOsrmRegistry registry;
    private final Executor zoneManagerTaskExecutor;

    /**
     * Kicks off the whole-map boot asynchronously, the same way {@code BootRecoveryService} does for
     * zones: the container becomes ready while graphs are still building.
     *
     * @param args Spring Boot startup arguments (unused)
     */
    @Override
    public void run(@NonNull ApplicationArguments args) {
        if (!config.isGlobalOsrmEnabled()) {
            for (ZoneProfile profile : ZoneProfile.values()) {
                registry.register(new GlobalOsrmInstance(profile));
            }
            log.info("Global OSRM: disabled by configuration (osrm.zone-manager.global-osrm-enabled=false)");
            return;
        }
        CompletableFuture.runAsync(this::startAll, zoneManagerTaskExecutor);
    }

    /**
     * Builds (when needed) and starts one instance per profile, sequentially — whole-map
     * preprocessing is memory-hungry, so profiles are not built in parallel, and the shared
     * {@link BuildSerializer} keeps them from overlapping with zone builds as well.
     * Every profile is registered up-front as {@link GlobalOsrmStatus#PENDING} so {@code GET /osrm}
     * lists a stable set.
     */
    void startAll() {
        Path basePbf;
        try {
            basePbf = Path.of(mapsService.ensureBasePbf());
        } catch (Exception e) {
            log.error("Global OSRM: base PBF unavailable, skipping global instances: {}", e.getMessage());
            return;
        }
        String pbfFingerprint = mapFingerprint.pbfFingerprint(basePbf);

        for (ZoneProfile profile : ZoneProfile.values()) {
            GlobalOsrmInstance instance = new GlobalOsrmInstance(profile);
            instance.status = GlobalOsrmStatus.PENDING;
            registry.register(instance);
        }
        for (ZoneProfile profile : ZoneProfile.values()) {
            startProfile(profile, basePbf, pbfFingerprint);
        }
    }

    private void startProfile(ZoneProfile profile, Path basePbf, String pbfFingerprint) {
        GlobalOsrmInstance instance = registry.find(profile).orElseGet(() -> {
            GlobalOsrmInstance created = new GlobalOsrmInstance(profile);
            registry.register(created);
            return created;
        });
        Path dir = Path.of(config.getGlobalProfileDir(profile));
        try {
            Files.createDirectories(dir);
            ensureMap(dir, profile, basePbf, pbfFingerprint, instance);
            startProcess(instance, dir);
        } catch (Exception e) {
            log.error("Global OSRM {}: startup failed", profile.name(), e);
            fail(instance, "global " + profile.name().toLowerCase() + " startup failed: " + e.getMessage());
        }
    }

    /**
     * Builds the whole-map graph for a profile when the artifacts are missing or stale (different
     * OSRM version or replaced base PBF).
     *
     * @param dir             profile graph directory
     * @param profile         routing profile
     * @param basePbf         whole-map source PBF
     * @param pbfFingerprint  identity of the base PBF
     * @param instance        tracking state, updated with the {@link GlobalOsrmStatus#BUILDING} status
     * @throws IOException on graph directory/file access failure
     */
    private void ensureMap(Path dir, ZoneProfile profile, Path basePbf, String pbfFingerprint,
                           GlobalOsrmInstance instance) throws IOException {
        if (mapFingerprint.isUsable(dir, pbfFingerprint)) {
            log.info("Global OSRM {}: graph already up to date in {}", profile.name(), dir);
            return;
        }

        instance.status = GlobalOsrmStatus.BUILDING;
        instance.error = null;
        log.info("Global OSRM {}: building whole-map graph from {} into {} (timeout {}s per stage)",
                profile.name(), basePbf, dir, config.getGlobalBuildTimeoutSeconds());

        try {
            buildSerializer.acquireGlobal();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BuildException("interrupted while waiting for the build slot", e);
        }
        try {
            int timeout = config.getGlobalBuildTimeoutSeconds();
            Path mapOutput = dir.resolve(FILE_OSRM_MAP_BASE);
            commandRunner.run(List.of(
                    BINARY_OSRM_EXTRACT, FLAG_EXTRACT, commandRunner.profileLuaPath(profile),
                    FLAG_OUTPUT, mapOutput.toString(), basePbf.toString()
            ), dir.toFile(), timeout);
            commandRunner.run(List.of(BINARY_OSRM_PARTITION, MAP_OSRM), dir.toFile(), timeout);
            commandRunner.run(List.of(BINARY_OSRM_CUSTOMIZE, MAP_OSRM), dir.toFile(), timeout);
            mapFingerprint.write(dir, pbfFingerprint);
        } finally {
            buildSerializer.release();
        }
        log.info("Global OSRM {}: whole-map graph built", profile.name());
    }

    private void startProcess(GlobalOsrmInstance instance, Path dir) {
        instance.status = GlobalOsrmStatus.STARTING;
        int port = portAllocator.reservePort(reserved -> instance.port = reserved);
        try {
            instance.osrm = launcher.launch(dir.resolve(FILE_OSRM_MAP_BASE), port);
            instance.osrmPid = instance.osrm.pid();
        } catch (Exception e) {
            fail(instance, "failed to start osrm-routed: " + e.getMessage());
            return;
        }

        if (launcher.waitRouteHealth(port, OSRM_HEALTH_TIMEOUT_SECONDS)) {
            instance.status = GlobalOsrmStatus.READY;
            instance.error = null;
            instance.retries = 0;
            log.info("Global OSRM {}: ready on port {} (pid={})", instance.profile.name(), port, instance.osrmPid);
        } else {
            launcher.kill(instance.osrm, "global-osrm-" + instance.profile);
            instance.osrm = null;
            instance.status = GlobalOsrmStatus.FAILED;
            instance.error = "startup timeout on port " + port;
            log.error("Global OSRM {}: startup timeout on port {}", instance.profile.name(), port);
        }
    }

    /**
     * @param profile routing profile
     * @return the loopback port of the profile's instance when it is serving traffic
     */
    public Optional<Integer> findPort(ZoneProfile profile) {
        return registry.find(profile)
                .filter(instance -> instance.status.isLive())
                .filter(instance -> instance.port != null)
                .map(instance -> instance.port);
    }

    /**
     * @param profile routing profile
     * @return the current status of the profile's instance, or {@code null} if it was never started
     */
    public GlobalOsrmStatus statusOf(ZoneProfile profile) {
        return registry.find(profile).map(instance -> instance.status).orElse(null);
    }

    /**
     * @param profile routing profile
     * @return the last failure reason of the profile's instance, or {@code null}
     */
    public String errorOf(ZoneProfile profile) {
        return registry.find(profile).map(instance -> instance.error).orElse(null);
    }

    /**
     * @return one status entry per profile managed by this instance
     */
    public List<GlobalOsrmStatusDTO> statuses() {
        return registry.statuses();
    }

    /**
     * Periodic health probing and restart, mirroring the per-zone supervisor.
     */
    @Scheduled(fixedDelay = 30_000)
    public void healthCheck() {
        if (!config.isGlobalOsrmEnabled()) {
            return;
        }
        for (GlobalOsrmInstance instance : registry.all()) {
            try {
                checkOne(instance);
            } catch (Exception e) {
                log.warn("Global OSRM {}: health check error: {}", instance.profile.name(), e.getMessage());
            }
        }
    }

    private void checkOne(GlobalOsrmInstance instance) {
        if (instance.port == null || isWholeMapBuildInProgress(instance)) {
            return;
        }
        if (launcher.ping(instance.port)) {
            if (instance.status != GlobalOsrmStatus.READY) {
                instance.status = GlobalOsrmStatus.READY;
                instance.error = null;
                instance.retries = 0;
                log.info("Global OSRM {}: recovered to ready", instance.profile.name());
            }
            return;
        }

        instance.retries++;
        if (instance.retries > MAX_HEALTH_RETRIES) {
            instance.status = GlobalOsrmStatus.DEGRADED;
            instance.error = "unhealthy after " + instance.retries + " retries";
            log.warn("Global OSRM {}: marked degraded ({} retries)", instance.profile.name(), instance.retries);
            return;
        }

        log.warn("Global OSRM {}: unhealthy, restart attempt {}/{}",
                instance.profile.name(), instance.retries, MAX_HEALTH_RETRIES);
        restart(instance);
    }

    private void restart(GlobalOsrmInstance instance) {
        launcher.kill(instance.osrm, "global-osrm-" + instance.profile);
        instance.osrm = null;
        Path dir = Path.of(config.getGlobalProfileDir(instance.profile));
        try {
            instance.osrm = launcher.launch(dir.resolve(FILE_OSRM_MAP_BASE), instance.port);
            instance.osrmPid = instance.osrm.pid();
        } catch (Exception e) {
            fail(instance, "restart failed: " + e.getMessage());
            return;
        }
        if (launcher.waitRouteHealth(instance.port, OSRM_HEALTH_TIMEOUT_SECONDS)) {
            instance.status = GlobalOsrmStatus.READY;
            instance.error = null;
            instance.retries = 0;
        } else {
            fail(instance, "restart timeout on port " + instance.port);
        }
    }

    private boolean isWholeMapBuildInProgress(GlobalOsrmInstance instance) {
        return instance.status == GlobalOsrmStatus.BUILDING || instance.status == GlobalOsrmStatus.PENDING;
    }

    private void fail(GlobalOsrmInstance instance, String error) {
        launcher.kill(instance.osrm, "global-osrm-" + instance.profile);
        instance.osrm = null;
        instance.status = GlobalOsrmStatus.FAILED;
        instance.error = error;
        log.error("Global OSRM {}: {}", instance.profile.name(), error);
    }

    /**
     * Stops every global instance on JVM exit.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutdown: stopping global OSRM instances");
        stopAll();
    }

    /**
     * Kills every global instance process; instance state is kept so {@link #statuses()} still
     * reports the profiles.
     */
    public void stopAll() {
        for (GlobalOsrmInstance instance : registry.all()) {
            try {
                launcher.kill(instance.osrm, "global-osrm-" + instance.profile);
                instance.osrm = null;
            } catch (Exception e) {
                log.warn("Failed to stop global OSRM {}: {}", instance.profile.name(), e.getMessage());
            }
        }
    }
}
