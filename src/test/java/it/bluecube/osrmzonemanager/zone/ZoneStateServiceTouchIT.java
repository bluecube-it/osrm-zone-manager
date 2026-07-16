package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

class ZoneStateServiceTouchIT extends BaseIT {

    @Autowired
    private ZoneStateService zoneStateService;
    @Autowired
    private ZoneRepository zoneRepository;

    @Test
    void shouldUpdateLastAccessTimestamp() {
        Instant before = Instant.now().minusSeconds(60);
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("touchzone12345")
                .lastAccess(before)
                .build();
        zoneRepository.save(zone);

        zoneStateService.touch("touchzone12345");

        ZoneEntity updated = zoneRepository.findById("touchzone12345").orElseThrow();
        Assertions.assertThat(updated.getLastAccess()).isAfter(before);
    }
}
