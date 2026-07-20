package it.bluecube.osrmzonemanager.zone;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/**
 * JPA repository for {@link ZoneEntity}. Accessed only via {@link ZoneStateService}.
 */
@Repository
public interface ZoneRepository extends JpaRepository<ZoneEntity, String> {

    /**
     * @param osrmPort  OSRM port number
     * @param vroomPort Vroom port number
     * @return true if any zone uses either port
     */
    boolean existsByOsrmPortOrVroomPort(int osrmPort, int vroomPort);

    /**
     * Deletes a zone by zone_id, bypassing the @Version optimistic lock check.
     * Zone deletion is a terminal operation — no version conflict possible by design
     * because the caller already holds the per-zone lock and has stopped all processes.
     *
     * @param zoneId zone identifier
     */
    @Modifying
    @Query("delete from ZoneEntity z where z.zoneId = :zoneId")
    void deleteByIdIgnoringVersion(String zoneId);
}
