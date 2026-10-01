package it.bluecube.osrmzonemanager.zone;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/**
 * Request body for POST /zones — polygon is required, lineStrings optional.
 */
public record ZoneInputDTO(
        /**
         * Valid GeoJSON polygon defining the zone geometry.
         */
        @NotNull JsonNode polygon,
        /**
         * Optional GeoJSON lineStrings for routing constraints.
         */
        JsonNode lineStrings,
        /**
         * Optional routing profile ({@code CAR}, {@code BUS}, any case; defaults to
         * {@link ZoneProfile#CAR} when omitted or null).
         */
        ZoneProfile profile
) {
}
