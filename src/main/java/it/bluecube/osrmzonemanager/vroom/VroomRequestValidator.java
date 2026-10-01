package it.bluecube.osrmzonemanager.vroom;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Replicates the request validation {@code vroom-express} performed in its {@code sizeCheckCallback}:
 * a payload must carry {@code vehicles} plus either {@code jobs} or {@code shipments}, and both the
 * location count ({@code jobs + 2 * shipments}) and the vehicle count are bounded by configuration.
 */
@Component
@RequiredArgsConstructor
public class VroomRequestValidator {

    static final String INVALID_INPUT_MESSAGE =
            "Invalid JSON object in request, please add vehicles and jobs or shipments to the object body";

    private final OsrmZoneManagerConfig config;

    /**
     * Validates a parsed VROOM request payload.
     *
     * @param body parsed request body
     * @throws VroomErrorException with vroom error code 2 (HTTP 400) for a malformed request,
     *                             or code 4 (HTTP 413) when a size bound is exceeded
     */
    public void validate(JsonNode body) {
        JsonNode jobs = body.path("jobs");
        JsonNode shipments = body.path("shipments");
        boolean hasJobs = !jobs.isMissingNode() && !jobs.isNull();
        boolean hasShipments = !shipments.isMissingNode() && !shipments.isNull();
        boolean hasVehicles = body.has("vehicles") && !body.path("vehicles").isNull();

        if ((!hasJobs && !hasShipments) || !hasVehicles) {
            throw VroomErrorException.input(INVALID_INPUT_MESSAGE);
        }

        long locations = 0;
        if (hasJobs) {
            locations += jobs.isArray() ? jobs.size() : 0;
        }
        if (hasShipments) {
            locations += 2L * (shipments.isArray() ? shipments.size() : 0);
        }
        if (locations > config.getVroomMaxLocations()) {
            throw VroomErrorException.tooLarge("Too many locations (" + locations
                    + ") in query, maximum is set to " + config.getVroomMaxLocations());
        }

        long vehicles = body.path("vehicles").isArray() ? body.path("vehicles").size() : 0;
        if (vehicles > config.getVroomMaxVehicles()) {
            throw VroomErrorException.tooLarge("Too many vehicles (" + vehicles
                    + ") in query, maximum is set to " + config.getVroomMaxVehicles());
        }
    }
}
