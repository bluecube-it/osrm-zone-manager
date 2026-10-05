package it.bluecube.osrmzonemanager.vroom;

import it.bluecube.test.BaseUnitTest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class VroomServiceVehicleProfileLowerCaseTest extends BaseUnitTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void lowerCasesUpperCaseVehicleProfiles() {
        JsonNode body = objectMapper.readTree("""
                {"vehicles":[{"id":0,"profile":"BUS"},{"id":1,"profile":"CAR"}],"shipments":[]}""");

        byte[] result = VroomService.lowerCaseVehicleProfiles(objectMapper, body);

        Assertions.assertThat(result).isNotNull();
        JsonNode normalized = objectMapper.readTree(result);
        Assertions.assertThat(normalized.path("vehicles").get(0).path("profile").asText()).isEqualTo("bus");
        Assertions.assertThat(normalized.path("vehicles").get(1).path("profile").asText()).isEqualTo("car");
    }

    @Test
    void trimsAndLowerCasesVehicleProfiles() {
        JsonNode body = objectMapper.readTree("""
                {"vehicles":[{"id":0,"profile":" BUS "}],"shipments":[]}""");

        byte[] result = VroomService.lowerCaseVehicleProfiles(objectMapper, body);

        Assertions.assertThat(result).isNotNull();
        Assertions.assertThat(objectMapper.readTree(result).path("vehicles").get(0).path("profile").asText())
                .isEqualTo("bus");
    }

    @Test
    void returnsNullWhenProfilesAreAbsent() {
        JsonNode body = objectMapper.readTree("""
                {"vehicles":[{"id":0},{"id":1}],"shipments":[]}""");

        Assertions.assertThat(VroomService.lowerCaseVehicleProfiles(objectMapper, body)).isNull();
    }

    @Test
    void returnsNullWhenProfilesAreAlreadyLowerCase() {
        JsonNode body = objectMapper.readTree("""
                {"vehicles":[{"id":0,"profile":"bus"}],"shipments":[]}""");

        Assertions.assertThat(VroomService.lowerCaseVehicleProfiles(objectMapper, body)).isNull();
    }

    @Test
    void returnsNullWhenProfilesAreNotTextual() {
        JsonNode body = objectMapper.readTree("""
                {"vehicles":[{"id":0,"profile":7}],"shipments":[]}""");

        Assertions.assertThat(VroomService.lowerCaseVehicleProfiles(objectMapper, body)).isNull();
    }

    @Test
    void returnsNullWhenVehiclesAreMissing() {
        JsonNode body = objectMapper.readTree("""
                {"shipments":[]}""");

        Assertions.assertThat(VroomService.lowerCaseVehicleProfiles(objectMapper, body)).isNull();
    }
}
