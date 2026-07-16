package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ZoneStateServiceFindAllRecoveryDataIT extends BaseIT {

    @Autowired
    private ZoneStateService zoneStateService;
    @Autowired
    private ZoneRepository zoneRepository;

    @Test
    void shouldReturnListOfRecoveryDtosForAllZones() {
        ZoneEntity a = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("recoverya12345")
                .polygonHash("hashA")
                .build();
        ZoneEntity b = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("recoveryb12345")
                .polygonHash("hashB")
                .build();
        zoneRepository.save(a);
        zoneRepository.save(b);

        var result = zoneStateService.findAllRecoveryData();

        Assertions.assertThat(result).hasSize(2);
        Assertions.assertThat(result).extracting(ZoneRecoveryDTO::zoneId).containsExactlyInAnyOrder("recoverya12345", "recoveryb12345");
    }
}
