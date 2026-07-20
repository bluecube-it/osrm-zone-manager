package it.bluecube.osrmzonemanager.runtime;

import it.bluecube.osrmzonemanager.maps.MapsService;
import it.bluecube.osrmzonemanager.zone.ZoneEntity;
import it.bluecube.osrmzonemanager.zone.ZoneRepository;
import it.bluecube.osrmzonemanager.zone.ZoneStatus;
import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class PortAllocatorServiceReservePortPairIT extends BaseIT {

    @Autowired
    private PortAllocatorService portAllocatorService;

    @Autowired
    private ZoneRepository zoneRepository;

    @MockitoBean
    private MapsService mapsService;

    @Test
    void shouldReserveFirstAvailablePortPair() {
        int[] ports = portAllocatorService.reservePortPair();

        Assertions.assertThat(ports).containsExactly(5001, 3001);
    }

    @Test
    void shouldSkipPortsAlreadyInZoneRegistry() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("reserved5001")
                .status(ZoneStatus.ACTIVE.name())
                .osrmPort(5001)
                .vroomPort(3001)
                .build();
        zoneRepository.save(zone);

        int[] ports = portAllocatorService.reservePortPair();

        Assertions.assertThat(ports).containsExactly(5002, 3002);
    }

    @Test
    void shouldThrowIllegalStateExceptionWhenPoolExhausted() {
        for (int offset = 1; offset <= 150; offset++) {
            ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                    .zoneId("z" + offset)
                    .status(ZoneStatus.ACTIVE.name())
                    .osrmPort(5000 + offset)
                    .vroomPort(3000 + offset)
                    .build();
            zoneRepository.save(zone);
        }

        Assertions.assertThatThrownBy(() -> portAllocatorService.reservePortPair())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("port pool exhausted");
    }
}
