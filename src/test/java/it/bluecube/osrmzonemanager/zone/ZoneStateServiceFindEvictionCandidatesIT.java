package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

class ZoneStateServiceFindEvictionCandidatesIT extends BaseIT {

    @Autowired
    private ZoneStateService zoneStateService;
    @Autowired
    private ZoneRepository zoneRepository;

    @Test
    void shouldReturnCandidatesWithZoneIdAndLastAccessForMatchingStatuses() {
        Instant access = Instant.now();
        ZoneEntity active = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("activeevict123")
                .status(ZoneStatus.ACTIVE.name())
                .lastAccess(access)
                .build();
        ZoneEntity building = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("buildevict1234")
                .status(ZoneStatus.BUILDING.name())
                .lastAccess(access)
                .build();
        zoneRepository.save(active);
        zoneRepository.save(building);

        var result = zoneStateService.findEvictionCandidates(List.of(ZoneStatus.ACTIVE.name()));

        Assertions.assertThat(result).hasSize(1);
        Assertions.assertThat(result.getFirst().zoneId()).isEqualTo("activeevict123");
        Assertions.assertThat(result.getFirst().lastAccess()).isEqualTo(access);
    }
}
