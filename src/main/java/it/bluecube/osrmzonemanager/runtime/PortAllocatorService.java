package it.bluecube.osrmzonemanager.runtime;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.zone.ZoneStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Allocates paired OSRM/Vroom port numbers for zones.
 *
 * <p>Candidate ports are derived from configured base offsets and checked
 * against two independent sources of truth: the {@link ZoneStateService}
 * (logical reservation — is this port already assigned to a zone?) and the
 * OS TCP stack (physical availability — is this port actually bindable right
 * now?). Both checks are required, since a port can be logically free but
 * physically occupied by an orphaned/zombie process, or vice versa.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortAllocatorService {

    private static final int PORT_SCAN_RANGE = 150;

    private final ZoneStateService zoneStateService;
    private final OsrmZoneManagerConfig config;

    /**
     * Reserves a pair of free ports (OSRM + Vroom) at the same offset from their
     * respective configured base ports.
     *
     * @return a two-element array {@code [osrmPort, vroomPort]}
     * @throws IllegalStateException if no free port pair is found within {@link #PORT_SCAN_RANGE}
     */
    public synchronized int[] reservePortPair() {
        int osrmStart = config.getOsrmPortStart();
        int vroomStart = config.getVroomPortStart();

        for (int offset = 1; offset <= PORT_SCAN_RANGE; offset++) {
            int osrmPort = osrmStart + offset;
            int vroomPort = vroomStart + offset;

            if (zoneStateService.existsByOsrmPortOrVroomPort(osrmPort, vroomPort)) {
                continue;
            }
            if (!isPortFree(osrmPort) || !isPortFree(vroomPort)) {
                log.debug("Port pair osrm={} vroom={} logically free but not bindable, skipping", osrmPort, vroomPort);
                continue;
            }

            log.debug("Reserved ports osrm={} vroom={}", osrmPort, vroomPort);
            return new int[]{osrmPort, vroomPort};
        }
        throw new IllegalStateException("port pool exhausted — tried offset 1.." + PORT_SCAN_RANGE);
    }

    public void releasePort(String kind, int port) {
        log.debug("ReleasePort {} {} (implicit: zone record deletion frees ports)", kind, port);
    }

    /**
     * Checks whether a TCP port can currently be bound on this host.
     *
     * @param port the port to check
     * @return {@code true} if the port is free at the OS level
     */
    private boolean isPortFree(int port) {
        try (var _ = new ServerSocket(port)) {
            return true;
        } catch (IOException _) {
            return false;
        }
    }
}
