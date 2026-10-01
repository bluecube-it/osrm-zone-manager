package it.bluecube.osrmzonemanager.vroom;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.test.BaseUnitTest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class VroomRequestValidatorTest extends BaseUnitTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private VroomRequestValidator validator;

    @BeforeEach
    void setUp() {
        OsrmZoneManagerConfig config = Mockito.mock(OsrmZoneManagerConfig.class);
        Mockito.lenient().when(config.getVroomMaxLocations()).thenReturn(1000);
        Mockito.lenient().when(config.getVroomMaxVehicles()).thenReturn(200);
        validator = new VroomRequestValidator(config);
    }

    @Test
    void acceptsJobsAndVehicles() {
        Assertions.assertThatCode(() -> validator.validate(json("""
                {"jobs":[{"id":1}],"vehicles":[{"id":0}]}"""))).doesNotThrowAnyException();
    }

    @Test
    void acceptsShipmentsAndVehicles() {
        Assertions.assertThatCode(() -> validator.validate(json("""
                        {"shipments":[{"pickup":{"id":1},"delivery":{"id":2}}],"vehicles":[{"id":0}]}""")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingVehicles() {
        VroomErrorException error = Assertions.catchThrowableOfType(
                () -> validator.validate(json("{\"jobs\":[{\"id\":1}]}")), VroomErrorException.class);

        Assertions.assertThat(error.code()).isEqualTo(2);
        Assertions.assertThat(error.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        Assertions.assertThat(error).hasMessage(VroomRequestValidator.INVALID_INPUT_MESSAGE);
    }

    @Test
    void rejectsMissingJobsAndShipments() {
        VroomErrorException error = Assertions.catchThrowableOfType(
                () -> validator.validate(json("{\"vehicles\":[{\"id\":0}]}")), VroomErrorException.class);

        Assertions.assertThat(error.code()).isEqualTo(2);
    }

    @Test
    void rejectsTooManyLocationsCountingShipmentsTwice() {
        StringBuilder jobs = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            jobs.append("{\"id\":").append(i).append("},");
        }
        String body = "{\"jobs\":["
                + jobs.substring(0, jobs.length() - 1)
                + "],\"shipments\":[{\"pickup\":{\"id\":1},\"delivery\":{\"id\":2}},"
                + "{\"pickup\":{\"id\":3},\"delivery\":{\"id\":4}}],\"vehicles\":[{\"id\":0}]}";

        VroomErrorException error = Assertions.catchThrowableOfType(
                () -> validator.validate(json(body)), VroomErrorException.class);

        Assertions.assertThat(error.code()).isEqualTo(4);
        Assertions.assertThat(error.httpStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        Assertions.assertThat(error).hasMessageContaining("Too many locations (1004)");
    }

    @Test
    void rejectsTooManyVehicles() {
        StringBuilder vehicles = new StringBuilder();
        for (int i = 0; i <= 200; i++) {
            vehicles.append("{\"id\":").append(i).append("},");
        }
        String body = "{\"jobs\":[],\"vehicles\":["
                + vehicles.substring(0, vehicles.length() - 1) + "]}";

        VroomErrorException error = Assertions.catchThrowableOfType(
                () -> validator.validate(json(body)), VroomErrorException.class);

        Assertions.assertThat(error.code()).isEqualTo(4);
        Assertions.assertThat(error).hasMessageContaining("Too many vehicles (201)");
    }

    private JsonNode json(String raw) {
        return mapper.readTree(raw);
    }
}
