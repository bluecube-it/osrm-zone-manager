package it.bluecube.osrmzonemanager.vroom;

import it.bluecube.osrmzonemanager.zone.ZoneEntity;
import it.bluecube.osrmzonemanager.zone.ZoneProfile;
import it.bluecube.osrmzonemanager.zone.ZoneRepository;
import it.bluecube.osrmzonemanager.zone.ZoneStatus;
import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Exercises the in-process VROOM endpoints against a stub {@code vroom} binary, keeping the
 * {@code vroom-express} HTTP contract: stdout relayed as body, exit code mapped to the status,
 * zone status gating, and the empty-body health probe.
 */
class VroomControllerIT extends BaseIT {

    private static final Path VROOM_BIN = createStubBinary();

    @Autowired
    private ZoneRepository zoneRepository;

    @DynamicPropertySource
    static void vroomProperties(DynamicPropertyRegistry registry) {
        registry.add("osrm.zone-manager.vroom-binary", () -> VROOM_BIN.toString());
    }

    private static Path createStubBinary() {
        try {
            Path script = Files.createTempFile("vroom-stub", ".sh");
            Files.writeString(script, """
                    #!/bin/sh
                    body=$(cat)
                    case "$body" in
                      *__input_error__*) echo '{"code":2,"error":"stub input error"}'; exit 2 ;;
                      *__routing_error__*) echo '{"code":3,"error":"stub routing error"}'; exit 3 ;;
                      *) echo '{"code":0,"summary":{"cost":42}}'; exit 0 ;;
                    esac
                    """);
            if (!script.toFile().setExecutable(true)) {
                throw new IllegalStateException("cannot make stub vroom binary executable: " + script);
            }
            return script;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void relaysStdoutAndMapsExitZeroToOk() {
        String zoneId = saveZone("activevroom01", ZoneStatus.ACTIVE, ZoneProfile.CAR);

        restTestClient.post()
                .uri("/{zoneId}/vroom", zoneId)
                .body("{\"jobs\":[{\"id\":1}],\"vehicles\":[{\"id\":0}]}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "*")
                .expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.summary.cost").isEqualTo(42);
    }

    @Test
    void mapsVroomInputErrorToBadRequestKeepingVroomBody() {
        String zoneId = saveZone("activevroom02", ZoneStatus.ACTIVE, ZoneProfile.CAR);

        restTestClient.post()
                .uri("/{zoneId}/vroom", zoneId)
                .body("{\"jobs\":[],\"vehicles\":[],\"__input_error__\":true}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo(2)
                .jsonPath("$.error").isEqualTo("stub input error");
    }

    @Test
    void mapsVroomRoutingErrorToInternalServerError() {
        String zoneId = saveZone("activevroom03", ZoneStatus.ACTIVE, ZoneProfile.CAR);

        restTestClient.post()
                .uri("/{zoneId}/vroom", zoneId)
                .body("{\"jobs\":[],\"vehicles\":[],\"__routing_error__\":true}")
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectBody()
                .jsonPath("$.code").isEqualTo(3);
    }

    @Test
    void rejectsPayloadWithoutVehicles() {
        String zoneId = saveZone("activevroom04", ZoneStatus.ACTIVE, ZoneProfile.CAR);

        restTestClient.post()
                .uri("/{zoneId}/vroom", zoneId)
                .body("{\"jobs\":[{\"id\":1}]}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo(2);
    }

    @Test
    void rejectsMalformedJson() {
        String zoneId = saveZone("activevroom05", ZoneStatus.ACTIVE, ZoneProfile.CAR);

        restTestClient.post()
                .uri("/{zoneId}/vroom", zoneId)
                .body("{not json")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo(2);
    }

    @Test
    void rejectsTooManyLocations() {
        String zoneId = saveZone("activevroom06", ZoneStatus.ACTIVE, ZoneProfile.CAR);
        StringBuilder jobs = new StringBuilder();
        for (int i = 0; i <= 1000; i++) {
            jobs.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append("}");
        }

        restTestClient.post()
                .uri("/{zoneId}/vroom", zoneId)
                .body("{\"jobs\":[" + jobs + "],\"vehicles\":[{\"id\":0}]}")
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectBody()
                .jsonPath("$.code").isEqualTo(4);
    }

    @Test
    void healthProbeReturnsEmptyBodyWhenBinarySucceeds() {
        String zoneId = saveZone("activevroom07", ZoneStatus.ACTIVE, ZoneProfile.CAR);

        restTestClient.get()
                .uri("/{zoneId}/vroom/health", zoneId)
                .exchange()
                .expectStatus().isOk()
                .expectBody().isEmpty();
    }

    @Test
    void busZoneStillAcceptsCarProfileRequests() {
        String zoneId = saveZone("activevroom08", ZoneStatus.ACTIVE, ZoneProfile.BUS);

        restTestClient.post()
                .uri("/{zoneId}/vroom", zoneId)
                .body("{\"jobs\":[],\"vehicles\":[{\"id\":0}]}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void degradedZoneStillAcceptsRequests() {
        String zoneId = saveZone("degradedvroom1", ZoneStatus.DEGRADED, ZoneProfile.CAR);

        restTestClient.post()
                .uri("/{zoneId}/vroom", zoneId)
                .body("{\"jobs\":[],\"vehicles\":[{\"id\":0}]}")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void rejectsZoneThatIsNotLive() {
        String zoneId = saveZone("buildingvroom1", ZoneStatus.BUILDING, ZoneProfile.CAR);

        restTestClient.post()
                .uri("/{zoneId}/vroom", zoneId)
                .body("{\"jobs\":[],\"vehicles\":[{\"id\":0}]}")
                .exchange()
                .expectStatus().isEqualTo(503);
    }

    @Test
    void returnsNotFoundForUnknownZone() {
        restTestClient.post()
                .uri("/{zoneId}/vroom", "missingzone1")
                .body("{\"jobs\":[],\"vehicles\":[{\"id\":0}]}")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.error").exists();
    }

    private String saveZone(String zoneId, ZoneStatus status, ZoneProfile profile) {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId(zoneId)
                .status(status.name())
                .profile(profile)
                .osrmPort(5123)
                .build();
        zoneRepository.save(zone);
        return zoneId;
    }
}
