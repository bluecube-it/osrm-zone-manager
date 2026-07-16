package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ZoneStateServiceDeleteByIdIT extends BaseIT {

    @Autowired
    private ZoneStateService zoneStateService;
    @Autowired
    private ZoneRepository zoneRepository;

    @Test
    void shouldDeleteExistingZoneRecord() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("deletezone1234")
                .build();
        zoneRepository.save(zone);

        zoneStateService.deleteById("deletezone1234");

        Assertions.assertThat(zoneRepository.findById("deletezone1234")).isEmpty();
    }
}
