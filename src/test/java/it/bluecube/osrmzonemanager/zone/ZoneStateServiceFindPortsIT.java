package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ZoneStateServiceFindPortsIT extends BaseIT {

    @Autowired
    private ZoneStateService zoneStateService;
    @Autowired
    private ZoneRepository zoneRepository;

    @Test
    void shouldReturnPortsWhenZoneExists() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("portzone123456")
                .osrmPort(5005)
                .vroomPort(3005)
                .build();
        zoneRepository.save(zone);

        var result = zoneStateService.findPorts("portzone123456");

        Assertions.assertThat(result).isPresent();
        Assertions.assertThat(result.get().osrmPort()).isEqualTo(5005);
        Assertions.assertThat(result.get().vroomPort()).isEqualTo(3005);
    }

    @Test
    void shouldReturnEmptyWhenNotFound() {
        var result = zoneStateService.findPorts("missing1234567");

        Assertions.assertThat(result).isEmpty();
    }
}
