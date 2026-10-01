package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ZoneStateServiceFindOsrmPortIT extends BaseIT {

    @Autowired
    private ZoneStateService zoneStateService;
    @Autowired
    private ZoneRepository zoneRepository;

    @Test
    void shouldReturnPortWhenZoneExists() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("portzone123456")
                .osrmPort(5005)
                .build();
        zoneRepository.save(zone);

        var result = zoneStateService.findOsrmPort("portzone123456");

        Assertions.assertThat(result).contains(5005);
    }

    @Test
    void shouldReturnEmptyWhenNotFound() {
        var result = zoneStateService.findOsrmPort("missing1234567");

        Assertions.assertThat(result).isEmpty();
    }
}
