package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ZoneStateServiceMarkZoneBuiltIT extends BaseIT {

    @Autowired
    private ZoneStateService zoneStateService;
    @Autowired
    private ZoneRepository zoneRepository;

    @Test
    void shouldSetStatusBuiltClearErrorAndSetLastBuildAt() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("builtzone12345")
                .status(ZoneStatus.BUILDING.name())
                .error("old error")
                .lastBuildAt(null)
                .build();
        zoneRepository.save(zone);

        zoneStateService.markZoneBuilt("builtzone12345");

        ZoneEntity updated = zoneRepository.findById("builtzone12345").orElseThrow();
        Assertions.assertThat(updated.getStatus()).isEqualTo(ZoneStatus.BUILT.name());
        Assertions.assertThat(updated.getError()).isEqualTo("");
        Assertions.assertThat(updated.getLastBuildAt()).isNotNull();
    }
}
