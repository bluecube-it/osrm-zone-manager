package it.bluecube.osrmzonemanager.runtime;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.global.GlobalOsrmRegistry;
import it.bluecube.osrmzonemanager.zone.ZoneStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.function.Consumer;

/**
 * Allocates OSRM port numbers for zones.
 *
 * <p>Candidate ports are derived from the configured base offset and checked
 * against three independent sources of truth: the {@link ZoneStateService}
 * (logical reservation — is this port already assigned to a zone?), the
 * {@link GlobalOsrmRegistry} (does a whole-map instance hold it?) and the
 * OS TCP stack (physical availability — is this port actually bindable right
 * now?). All checks are required, since a port can be logically free but
 * physically occupied by an orphaned/zombie process, or vice versa.
 *
 * <p>Ports are freed implicitly when the zone record is deleted: there is no explicit
 * release API, so ports held by FAILED zones (which may still have zombie processes)
 * are not immediately reused.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortAllocatorService {

    private static final int PORT_SCAN_RANGE = 150;

    private final ZoneStateService zoneStateService;
    private final GlobalOsrmRegistry globalOsrmRegistry;
    private final OsrmZoneManagerConfig config;

    /**
     * Reserves a free OSRM port.
     *
     * @return the reserved port number
     * @throws IllegalStateException if no free port is found within {@link #PORT_SCAN_RANGE}
     */
    public synchronized int reservePort() {
        return reservePort(null);
    }

    /**
     * Reserves a free OSRM port, publishing the reservation to {@code onReserved} before the
     * allocator lock is released.
     *
     * <p>The callback runs inside the critical section so callers can record the port in their own
     * registry while no other allocation can happen — this closes the window in which two callers
     * could otherwise be handed the same port.
     *
     * @param onReserved callback invoked with the reserved port, may be {@code null}
     * @return the reserved port number
     * @throws IllegalStateException if no free port is found within {@link #PORT_SCAN_RANGE}
     */
    public synchronized int reservePort(Consumer<Integer> onReserved) {
        int osrmStart = config.getOsrmPortStart();

        for (int offset = 1; offset <= PORT_SCAN_RANGE; offset++) {
            int osrmPort = osrmStart + offset;

            if (zoneStateService.existsByOsrmPort(osrmPort)) {
                continue;
            }
            if (globalOsrmRegistry.reservesPort(osrmPort)) {
                log.debug("Port osrm={} reserved by a global instance, skipping", osrmPort);
                continue;
            }
            if (!isPortFree(osrmPort)) {
                log.debug("Port osrm={} logically free but not bindable, skipping", osrmPort);
                continue;
            }

            if (onReserved != null) {
                onReserved.accept(osrmPort);
            }
            log.debug("Reserved port osrm={}", osrmPort);
            return osrmPort;
        }
        throw new IllegalStateException("port pool exhausted — tried offset 1.." + PORT_SCAN_RANGE);
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
