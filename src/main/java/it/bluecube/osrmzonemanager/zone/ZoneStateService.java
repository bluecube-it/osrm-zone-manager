package it.bluecube.osrmzonemanager.zone;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;


/**
 * Owns all {@link ZoneRepository} access — the single persistence gateway for zone state.
 * Any class outside the {@code zone} package that needs to read or mutate zone persistence
 * must go through this service, never the repository directly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZoneStateService {
    private final ZoneRepository zoneRepository;

    // --- entity access (package-private — only ZoneService same-package callers) ---

    /**
     * @param zoneId zone identifier
     * @return the zone entity if present
     */
    Optional<ZoneEntity> findById(String zoneId) {
        return zoneRepository.findById(zoneId);
    }

    /**
     * Returns osrm + vroom port pair for a zone. Returns empty if zone not found.
     * Used by external services (BuildPipelineService, ProcessSupervisorService) to avoid entity leak.
     */
    public Optional<ZonePorts> findPorts(String zoneId) {
        return zoneRepository.findById(zoneId)
                .map(zone -> new ZonePorts(zone.getOsrmPort(), zone.getVroomPort()));
    }

    // package-private — only ZoneService (same package) uses these entity returns

    /**
     * @param zone zone entity to save
     * @return the saved zone entity
     */
    Optional<ZoneEntity> save(ZoneEntity zone) {
        return Optional.of(zoneRepository.save(zone));
    }

    /**
     * @return all zone entities
     */
    List<ZoneEntity> findAll() {
        return zoneRepository.findAll();
    }

    // --- public queries (no entity leak) ---

    /**
     * @param osrmPort  OSRM port number
     * @param vroomPort Vroom port number
     * @return true if any zone uses either port
     */
    public boolean existsByOsrmPortOrVroomPort(int osrmPort, int vroomPort) {
        return zoneRepository.existsByOsrmPortOrVroomPort(osrmPort, vroomPort);
    }

    /**
     * Deletes the zone record.
     *
     * @param zoneId zone identifier
     */
    @Transactional
    public void deleteById(String zoneId) {
        zoneRepository.deleteByIdIgnoringVersion(zoneId);
    }

    /**
     * Deletes all zone records.
     */
    @Transactional
    public void deleteAll() {
        zoneRepository.deleteAllInBatch();
    }

    // --- domain-specific mutation helpers ---

    /**
     * Updates {@code lastAccess} to now.
     *
     * @param zoneId zone identifier
     */
    @Transactional
    public void touch(String zoneId) {
        zoneRepository.findById(zoneId).ifPresent(zone -> {
            zone.setLastAccess(Instant.now());
            zoneRepository.save(zone);
        });
    }

    /**
     * Transitions zone to BUILT, resets error, updates lastBuildAt.
     *
     * @param zoneId zone identifier
     */
    @Transactional
    public void markZoneBuilt(String zoneId) {
        zoneRepository.findById(zoneId).ifPresent(zone -> {
            zone.setStatus(ZoneStatus.BUILT.name());
            zone.setLastBuildAt(Instant.now());
            zone.setError("");
            zoneRepository.save(zone);
        });
    }

    /**
     * Transitions zone to FAILED with the given error message.
     *
     * @param zoneId zone identifier
     * @param error  failure reason
     */
    @Transactional
    public void markZoneFailed(String zoneId, String error) {
        zoneRepository.findById(zoneId).ifPresent(zone -> {
            zone.setStatus(ZoneStatus.FAILED.name());
            zone.setError(error);
            zoneRepository.save(zone);
        });
    }

    /**
     * Transitions zone to BUILDING.
     *
     * @param zoneId zone identifier
     */
    @Transactional
    public void markZoneBuilding(String zoneId) {
        zoneRepository.findById(zoneId).ifPresent(zone -> {
            zone.setStatus(ZoneStatus.BUILDING.name());
            zoneRepository.save(zone);
        });
    }

    /**
     * Transitions zone to ACTIVE, records PIDs, resets error.
     *
     * @param zoneId   zone identifier
     * @param osrmPid  OSRM process ID
     * @param vroomPid Vroom process ID
     */
    @Transactional
    public void markZoneActive(String zoneId, long osrmPid, long vroomPid) {
        zoneRepository.findById(zoneId).ifPresent(zone -> {
            zone.setStatus(ZoneStatus.ACTIVE.name());
            zone.setOsrmPid(osrmPid);
            zone.setVroomPid(vroomPid);
            zone.setError("");
            zoneRepository.save(zone);
        });
    }

    /**
     * Transitions zone to DEGRADED, optionally setting an error message.
     *
     * @param zoneId zone identifier
     * @param error  optional error message
     */
    @Transactional
    public void markZoneDegraded(String zoneId, String error) {
        zoneRepository.findById(zoneId).ifPresent(zone -> {
            zone.setStatus(ZoneStatus.DEGRADED.name());
            if (error != null) {
                zone.setError(error);
            }
            zoneRepository.save(zone);
        });
    }

    /**
     * Conditionally updates status and error if zone currently has the given status.
     *
     * @param zoneId zone identifier
     * @param status expected current status
     * @param error  optional new error
     */
    @Transactional
    public void setStatusIfPresent(String zoneId, String status, String error) {
        zoneRepository.findById(zoneId).ifPresent(zone -> {
            zone.setStatus(status);
            if (error != null) {
                zone.setError(error);
            }
            zoneRepository.save(zone);
        });
    }

    /**
     * Sets recovery state to FAILED.
     *
     * @param zoneId zone identifier
     * @param error  failure reason
     */
    @Transactional
    public void setRecoveryFailed(String zoneId, String error) {
        zoneRepository.findById(zoneId).ifPresent(zone -> {
            zone.setStatus(ZoneStatus.FAILED.name());
            zone.setError(error);
            zoneRepository.save(zone);
        });
    }

    /**
     * Returns the recovery data (geojson + hash) needed by BootRecoveryService to rebuild zones.
     */
    public Optional<ZoneRecoveryDTO> findRecoveryData(String zoneId) {
        return zoneRepository.findById(zoneId).map(zone -> new ZoneRecoveryDTO(
                zone.getZoneId(),
                zone.getStatus(),
                zone.getPolygonHash(),
                zone.getPolygonGeojson(),
                zone.getLineStringsGeojson()
        ));
    }

    /**
     * @return recovery data for all zones
     */
    public List<ZoneRecoveryDTO> findAllRecoveryData() {
        return zoneRepository.findAll().stream()
                .map(zone -> new ZoneRecoveryDTO(
                        zone.getZoneId(),
                        zone.getStatus(),
                        zone.getPolygonHash(),
                        zone.getPolygonGeojson(),
                        zone.getLineStringsGeojson()
                ))
                .toList();
    }

}
