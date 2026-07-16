package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ZoneStateServiceMarkZoneActiveIT extends BaseIT {

    @Autowired
    private ZoneStateService zoneStateService;
    @Autowired
    private ZoneRepository zoneRepository;

    @Test
    void shouldSetStatusActivePidsAndClearError() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("activezone12345")
                .status(ZoneStatus.STARTING.name())
                .error("old error")
                .build();
        zoneRepository.save(zone);

        zoneStateService.markZoneActive("activezone12345", 123L, 456L);

        ZoneEntity updated = zoneRepository.findById("activezone12345").orElseThrow();
        Assertions.assertThat(updated.getStatus()).isEqualTo(ZoneStatus.ACTIVE.name());
        Assertions.assertThat(updated.getOsrmPid()).isEqualTo(123L);
        Assertions.assertThat(updated.getVroomPid()).isEqualTo(456L);
        Assertions.assertThat(updated.getError()).isEqualTo("");
    }
}
