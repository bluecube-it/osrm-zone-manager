package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ZoneStateServiceMarkZoneEvictingIT extends BaseIT {

    @Autowired
    private ZoneStateService zoneStateService;
    @Autowired
    private ZoneRepository zoneRepository;

    @Test
    void shouldSetStatusEvicting() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("evictzone12345")
                .status(ZoneStatus.ACTIVE.name())
                .build();
        zoneRepository.save(zone);

        zoneStateService.markZoneEvicting("evictzone12345");

        ZoneEntity updated = zoneRepository.findById("evictzone12345").orElseThrow();
        Assertions.assertThat(updated.getStatus()).isEqualTo(ZoneStatus.EVICTING.name());
    }
}
