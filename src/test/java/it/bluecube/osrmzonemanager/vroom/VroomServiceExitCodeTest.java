package it.bluecube.osrmzonemanager.vroom;

import it.bluecube.test.BaseUnitTest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

class VroomServiceExitCodeTest extends BaseUnitTest {

    private static final byte[] STDOUT = "{\"code\":0,\"summary\":{}}".getBytes(StandardCharsets.UTF_8);

    @Test
    void exitZeroMapsToOkWithStdoutBody() {
        ResponseEntity<byte[]> response = VroomService.mapExitCode(0, STDOUT, false);

        Assertions.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Assertions.assertThat(response.getBody()).isEqualTo(STDOUT);
    }

    @Test
    void exitTwoMapsToBadRequest() {
        byte[] error = "{\"code\":2,\"error\":\"input\"}".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<byte[]> response = VroomService.mapExitCode(2, error, false);

        Assertions.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Assertions.assertThat(response.getBody()).isEqualTo(error);
    }

    @Test
    void internalAndRoutingErrorsMapToInternalServerError() {
        Assertions.assertThat(VroomService.mapExitCode(1, STDOUT, false).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Assertions.assertThat(VroomService.mapExitCode(3, STDOUT, false).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Assertions.assertThat(VroomService.mapExitCode(137, STDOUT, false).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void emptyStdoutFallsBackToInternalErrorBody() {
        ResponseEntity<byte[]> response = VroomService.mapExitCode(137, new byte[0], false);

        Assertions.assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"code\":1,\"error\":\"Internal error\"}");
    }

    @Test
    void healthProbeReturnsStatusOnly() {
        ResponseEntity<byte[]> ok = VroomService.mapExitCode(0, STDOUT, true);
        Assertions.assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        Assertions.assertThat(ok.getBody()).isNull();

        ResponseEntity<byte[]> ko = VroomService.mapExitCode(2, STDOUT, true);
        Assertions.assertThat(ko.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Assertions.assertThat(ko.getBody()).isNull();
    }
}
