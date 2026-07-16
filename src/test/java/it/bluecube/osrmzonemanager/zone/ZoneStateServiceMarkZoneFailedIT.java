package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ZoneStateServiceMarkZoneFailedIT extends BaseIT {

    @Autowired
    private ZoneStateService zoneStateService;
    @Autowired
    private ZoneRepository zoneRepository;

    @Test
    void shouldSetStatusFailedAndError() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("failzone123456")
                .status(ZoneStatus.BUILDING.name())
                .build();
        zoneRepository.save(zone);

        zoneStateService.markZoneFailed("failzone123456", "build failed");

        ZoneEntity updated = zoneRepository.findById("failzone123456").orElseThrow();
        Assertions.assertThat(updated.getStatus()).isEqualTo(ZoneStatus.FAILED.name());
        Assertions.assertThat(updated.getError()).isEqualTo("build failed");
    }
}
