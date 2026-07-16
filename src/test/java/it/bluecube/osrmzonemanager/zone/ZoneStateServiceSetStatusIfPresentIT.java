package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ZoneStateServiceSetStatusIfPresentIT extends BaseIT {

    @Autowired
    private ZoneStateService zoneStateService;
    @Autowired
    private ZoneRepository zoneRepository;

    @Test
    void shouldUpdateStatusAndErrorWhenZoneExists() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("setstatus12345")
                .status(ZoneStatus.ACTIVE.name())
                .error("old")
                .build();
        zoneRepository.save(zone);

        zoneStateService.setStatusIfPresent("setstatus12345", ZoneStatus.DEGRADED.name(), "new");

        ZoneEntity updated = zoneRepository.findById("setstatus12345").orElseThrow();
        Assertions.assertThat(updated.getStatus()).isEqualTo(ZoneStatus.DEGRADED.name());
        Assertions.assertThat(updated.getError()).isEqualTo("new");
    }

    @Test
    void shouldDoNothingWhenZoneDoesNotExist() {
        zoneStateService.setStatusIfPresent("missing1234567", ZoneStatus.FAILED.name(), "error");

        Assertions.assertThat(zoneRepository.findById("missing1234567")).isEmpty();
    }
}
