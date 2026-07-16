package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

class ZoneControllerListZonesIT extends BaseIT {

    @Autowired
    private ZoneRepository zoneRepository;

    @Test
    void shouldReturnEmptyList() {
        restTestClient.get()
                .uri("/zones")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void shouldReturnZonesSortedByLastAccessDesc() {
        Instant now = Instant.now();
        ZoneEntity a = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("zonea1234567")
                .status(ZoneStatus.ACTIVE.name())
                .lastAccess(now.minusSeconds(60))
                .build();
        ZoneEntity b = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("zoneb1234567")
                .status(ZoneStatus.BUILDING.name())
                .lastAccess(now)
                .build();
        zoneRepository.save(a);
        zoneRepository.save(b);

        restTestClient.get()
                .uri("/zones")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].zoneId").isEqualTo("zoneb1234567")
                .jsonPath("$[1].zoneId").isEqualTo("zonea1234567");
    }

    @Test
    void shouldReturnZonesWithoutProcessFieldByDefault() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("zonec1234567")
                .status(ZoneStatus.ACTIVE.name())
                .build();
        zoneRepository.save(zone);

        restTestClient.get()
                .uri("/zones")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].zoneId").isEqualTo("zonec1234567")
                .jsonPath("$[0].status").isEqualTo(ZoneStatus.ACTIVE.name())
                .jsonPath("$[0].osrmPort").isEqualTo(5001)
                .jsonPath("$[0].vroomPort").isEqualTo(3001)
                .jsonPath("$[0].process").doesNotExist();
    }
}
