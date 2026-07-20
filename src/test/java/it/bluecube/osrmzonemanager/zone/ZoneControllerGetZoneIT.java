package it.bluecube.osrmzonemanager.zone;

import it.bluecube.osrmzonemanager.runtime.ProcessSupervisorService;
import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class ZoneControllerGetZoneIT extends BaseIT {

    @MockitoBean
    private ProcessSupervisorService processSupervisorService;

    @Autowired
    private ZoneRepository zoneRepository;

    @Test
    void shouldReturnZoneWhenFound() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("zonefound12345")
                .status(ZoneStatus.ACTIVE.name())
                .build();
        zoneRepository.save(zone);
        Mockito.when(processSupervisorService.isZoneRunning("zonefound12345")).thenReturn(false);

        restTestClient.get()
                .uri("/zones/{zoneId}", "zonefound12345")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.zoneId").isEqualTo("zonefound12345")
                .jsonPath("$.status").isEqualTo(ZoneStatus.ACTIVE.name())
                .jsonPath("$.process").doesNotExist();
    }

    @Test
    void shouldReturnNotFoundForNonExistentZone() {
        restTestClient.get()
                .uri("/zones/{zoneId}", "nonexistent123")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").exists();
    }

    @Test
    void shouldReturnProcessRunningWhenSupervisorSaysRunning() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("zonerunning123")
                .status(ZoneStatus.ACTIVE.name())
                .build();
        zoneRepository.save(zone);
        Mockito.when(processSupervisorService.isZoneRunning("zonerunning123")).thenReturn(true);

        restTestClient.get()
                .uri("/zones/{zoneId}", "zonerunning123")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.process").isEqualTo("running");
    }

    @Test
    void shouldReturnProcessNullWhenSupervisorSaysNotRunning() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("zonenotrunning12")
                .status(ZoneStatus.ACTIVE.name())
                .build();
        zoneRepository.save(zone);
        Mockito.when(processSupervisorService.isZoneRunning("zonenotrunning12")).thenReturn(false);

        restTestClient.get()
                .uri("/zones/{zoneId}", "zonenotrunning12")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.process").doesNotExist();
    }
}
