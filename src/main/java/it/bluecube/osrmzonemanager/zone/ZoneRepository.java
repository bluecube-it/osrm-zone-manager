package it.bluecube.osrmzonemanager.zone;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * JPA repository for {@link ZoneEntity}. Accessed only via {@link ZoneStateService}.
 */
@Repository
public interface ZoneRepository extends JpaRepository<ZoneEntity, String> {

    /**
     * @param statuses status values to filter by
     * @return zones matching any of the given statuses
     */
    List<ZoneEntity> findByStatusIn(List<String> statuses);

    /**
     * @param osrmPort OSRM port number
     * @param vroomPort Vroom port number
     * @return true if any zone uses either port
     */
    boolean existsByOsrmPortOrVroomPort(int osrmPort, int vroomPort);
}
