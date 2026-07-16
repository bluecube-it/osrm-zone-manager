package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ZoneStateServiceMarkZoneBuildingIT extends BaseIT {

    @Autowired
    private ZoneStateService zoneStateService;
    @Autowired
    private ZoneRepository zoneRepository;

    @Test
    void shouldSetStatusBuilding() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("buildingzone1234")
                .status(ZoneStatus.FAILED.name())
                .build();
        zoneRepository.save(zone);

        zoneStateService.markZoneBuilding("buildingzone1234");

        ZoneEntity updated = zoneRepository.findById("buildingzone1234").orElseThrow();
        Assertions.assertThat(updated.getStatus()).isEqualTo(ZoneStatus.BUILDING.name());
    }
}
