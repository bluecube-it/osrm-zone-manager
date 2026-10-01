package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ZoneStateServiceExistsByOsrmPortIT extends BaseIT {

    @Autowired
    private ZoneStateService zoneStateService;
    @Autowired
    private ZoneRepository zoneRepository;

    @Test
    void shouldReturnTrueWhenPortMatches() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("existsport1234")
                .osrmPort(5010)
                .build();
        zoneRepository.save(zone);

        Assertions.assertThat(zoneStateService.existsByOsrmPort(5010)).isTrue();
    }

    @Test
    void shouldReturnFalseWhenPortDoesNotMatch() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("existsport1234")
                .osrmPort(5010)
                .build();
        zoneRepository.save(zone);

        Assertions.assertThat(zoneStateService.existsByOsrmPort(9999)).isFalse();
    }
}
