package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ZoneStateServiceExistsByOsrmPortOrVroomPortIT extends BaseIT {

    @Autowired
    private ZoneStateService zoneStateService;
    @Autowired
    private ZoneRepository zoneRepository;

    @Test
    void shouldReturnTrueWhenEitherPortMatches() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("existsport1234")
                .osrmPort(5010)
                .vroomPort(3010)
                .build();
        zoneRepository.save(zone);

        Assertions.assertThat(zoneStateService.existsByOsrmPortOrVroomPort(5010, 9999)).isTrue();
        Assertions.assertThat(zoneStateService.existsByOsrmPortOrVroomPort(9999, 3010)).isTrue();
        Assertions.assertThat(zoneStateService.existsByOsrmPortOrVroomPort(5010, 3010)).isTrue();
    }

    @Test
    void shouldReturnFalseWhenNeitherPortMatches() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("existsport1234")
                .osrmPort(5010)
                .vroomPort(3010)
                .build();
        zoneRepository.save(zone);

        Assertions.assertThat(zoneStateService.existsByOsrmPortOrVroomPort(8888, 9999)).isFalse();
    }
}
