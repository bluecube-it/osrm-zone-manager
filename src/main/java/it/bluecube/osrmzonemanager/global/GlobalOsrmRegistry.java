package it.bluecube.osrmzonemanager.global;

import it.bluecube.osrmzonemanager.zone.ZoneProfile;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of the global (whole base map) OSRM instances — one per
 * {@link ZoneProfile}.
 *
 * <p>Deliberately separate from the zone registry and from
 * {@link it.bluecube.osrmzonemanager.runtime.PortAllocatorService}: the allocator consults it to
 * avoid handing a zone a port already taken by a global instance, while this class keeps no
 * dependency on the allocator, so no bean cycle is created.
 */
@Component
public class GlobalOsrmRegistry {

    private final Map<ZoneProfile, GlobalOsrmInstance> instances = new ConcurrentHashMap<>();

    /**
     * @param port a TCP port
     * @return {@code true} if a global instance currently holds this port
     */
    public boolean reservesPort(int port) {
        return instances.values().stream()
                .anyMatch(instance -> instance.port != null && instance.port == port);
    }

    /**
     * Registers (or replaces) the tracking state for a profile.
     *
     * @param instance the instance state
     */
    void register(GlobalOsrmInstance instance) {
        instances.put(instance.profile, instance);
    }

    /**
     * @param profile routing profile
     * @return the tracking state for the profile, if the profile was started
     */
    Optional<GlobalOsrmInstance> find(ZoneProfile profile) {
        return Optional.ofNullable(instances.get(profile));
    }

    /**
     * @return all registered instances
     */
    Collection<GlobalOsrmInstance> all() {
        return List.copyOf(instances.values());
    }

    /**
     * @return the registered instances, keyed by profile, as transport DTOs
     */
    public List<GlobalOsrmStatusDTO> statuses() {
        return instances.values().stream()
                .map(instance -> new GlobalOsrmStatusDTO(
                        instance.profile.name(),
                        instance.status.name(),
                        instance.port,
                        instance.error))
                .toList();
    }
}
