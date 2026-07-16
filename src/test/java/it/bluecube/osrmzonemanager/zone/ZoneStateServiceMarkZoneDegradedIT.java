package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ZoneStateServiceMarkZoneDegradedIT extends BaseIT {

    @Autowired
    private ZoneStateService zoneStateService;
    @Autowired
    private ZoneRepository zoneRepository;

    @Test
    void shouldSetStatusDegradedAndErrorWhenNotNull() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("degradedzone1234")
                .status(ZoneStatus.ACTIVE.name())
                .build();
        zoneRepository.save(zone);

        zoneStateService.markZoneDegraded("degradedzone1234", "process crashed");

        ZoneEntity updated = zoneRepository.findById("degradedzone1234").orElseThrow();
        Assertions.assertThat(updated.getStatus()).isEqualTo(ZoneStatus.DEGRADED.name());
        Assertions.assertThat(updated.getError()).isEqualTo("process crashed");
    }

    @Test
    void shouldKeepExistingErrorWhenNullPassed() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("degradedzone1234")
                .status(ZoneStatus.ACTIVE.name())
                .error("existing")
                .build();
        zoneRepository.save(zone);

        zoneStateService.markZoneDegraded("degradedzone1234", null);

        ZoneEntity updated = zoneRepository.findById("degradedzone1234").orElseThrow();
        Assertions.assertThat(updated.getStatus()).isEqualTo(ZoneStatus.DEGRADED.name());
        Assertions.assertThat(updated.getError()).isEqualTo("existing");
    }
}
