package it.bluecube.osrmzonemanager.runtime;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Launches and probes a single {@code osrm-routed} process on a loopback port.
 *
 * <p>Shared by {@link ProcessSupervisorService} (per-zone instances) and the global whole-map
 * instances served under {@code /osrm/{profile}/**}: both need the same argv, health-probe
 * semantics and descendant-killing behaviour.
 *
 * <p>The probe is deliberately tolerant: {@code osrm-routed} answers {@code 400} when the loaded
 * graph has no snappable segment for the probe coordinates, which still proves the server is up.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OsrmProcessLauncher {

    private static final int PING_TIMEOUT_MS = 5_000;
    private static final String BINARY_OSRM_ROUTED = "osrm-routed";
    private static final String FLAG_ALGORITHM = "--algorithm";
    private static final String ALGORITHM_MLD = "mld";
    private static final String FLAG_IP = "--ip";
    private static final String FLAG_PORT = "--port";
    private static final String FLAG_MMAP = "--mmap";
    private static final String LOCALHOST = "127.0.0.1";
    private static final String HTTP_SCHEME = "http://";
    private static final String ROUTE_PATH_DRIVING = "/route/v1/driving/0,0;0,0";

    private final OsrmZoneManagerConfig config;
    private final RestClient pingClient = buildPingClient();

    private static RestClient buildPingClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(PING_TIMEOUT_MS);
        factory.setReadTimeout(PING_TIMEOUT_MS);
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Starts {@code osrm-routed} in MLD mode on {@code 127.0.0.1:<port>} with the given map base.
     *
     * @param mapBase OSRM map base path (no extension, e.g. {@code /data/zones/<id>/map})
     * @param port    loopback port to bind
     * @return the started process
     * @throws IOException if the process cannot be started
     */
    public Process launch(Path mapBase, int port) throws IOException {
        List<String> command = new ArrayList<>(List.of(
                BINARY_OSRM_ROUTED, FLAG_ALGORITHM, ALGORITHM_MLD,
                FLAG_IP, LOCALHOST, FLAG_PORT, String.valueOf(port), mapBase.toString()
        ));
        if (config.isOsrmMmap()) {
            command.add(FLAG_MMAP);
        }
        log.info("Starting osrm-routed on port {} (map={})", port, mapBase);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        return pb.start();
    }

    /**
     * Waits until the OSRM instance on {@code port} answers the route health probe.
     *
     * @param port           loopback port
     * @param timeoutSeconds wall-clock budget
     * @return {@code true} if the instance became healthy before the deadline
     */
    public boolean waitRouteHealth(int port, int timeoutSeconds) {
        Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
        while (Instant.now().isBefore(deadline)) {
            if (ping(port)) {
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

    /**
     * Probes the OSRM route endpoint on {@code port}. Any HTTP answer below 500 counts as healthy —
     * only transport failures and 5xx responses are unhealthy.
     *
     * @param port loopback port
     * @return {@code true} if OSRM answered
     */
    public boolean ping(int port) {
        String url = HTTP_SCHEME + LOCALHOST + ":" + port + ROUTE_PATH_DRIVING;
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

    /**
     * Kills a process and its descendants, waiting for a graceful exit before forcing.
     *
     * @param process the process to kill, may be {@code null}
     * @param label   label used in log messages
     */
    public void kill(Process process, String label) {
        if (process == null || !process.isAlive()) {
            return;
        }
        try {
            killDescendants(process, label);
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        log.debug("Killed {} (pid={})", label, process.pid());
    }

    private void killDescendants(Process process, String label) {
        try {
            process.descendants().forEach(ph -> {
                try {
                    ph.destroy();
                    try {
                        ph.onExit().get(1, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.debug("Descendant {} of {} did not terminate within 1s, forcibly killing", ph.pid(), label);
                        ph.destroyForcibly();
                    }
                } catch (Exception e) {
                    log.debug("Failed to kill descendant of {} (pid={}): {}", label, ph.pid(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.debug("Failed to enumerate descendants of {} (pid={}): {}", label, process.pid(), e.getMessage());
        }
    }
}
