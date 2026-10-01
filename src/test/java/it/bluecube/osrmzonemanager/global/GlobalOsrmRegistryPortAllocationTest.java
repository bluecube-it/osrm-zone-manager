package it.bluecube.osrmzonemanager.global;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.runtime.PortAllocatorService;
import it.bluecube.osrmzonemanager.zone.ZoneProfile;
import it.bluecube.osrmzonemanager.zone.ZoneStateService;
import it.bluecube.test.BaseUnitTest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

class GlobalOsrmRegistryPortAllocationTest extends BaseUnitTest {

    @Mock
    private ZoneStateService zoneStateService;
    @Mock
    private OsrmZoneManagerConfig config;

    private GlobalOsrmRegistry registry;
    private PortAllocatorService portAllocator;

    @BeforeEach
    void setUp() {
        registry = new GlobalOsrmRegistry();
        Mockito.lenient().when(config.getOsrmPortStart()).thenReturn(5000);
        portAllocator = new PortAllocatorService(zoneStateService, registry, config);
    }

    @Test
    void shouldSkipPortsHeldByGlobalInstances() {
        GlobalOsrmInstance instance = new GlobalOsrmInstance(ZoneProfile.CAR);
        instance.port = 5001;
        instance.status = GlobalOsrmStatus.READY;
        registry.register(instance);

        int port = portAllocator.reservePort();

        Assertions.assertThat(port).isEqualTo(5002);
    }

    @Test
    void shouldPublishReservationInsideAllocatorLock() {
        GlobalOsrmInstance instance = new GlobalOsrmInstance(ZoneProfile.BUS);
        registry.register(instance);

        int port = portAllocator.reservePort(reserved -> instance.port = reserved);

        Assertions.assertThat(instance.port).isEqualTo(port);
        Assertions.assertThat(registry.reservesPort(port)).isTrue();
        Assertions.assertThat(portAllocator.reservePort()).isEqualTo(port + 1);
    }
}
