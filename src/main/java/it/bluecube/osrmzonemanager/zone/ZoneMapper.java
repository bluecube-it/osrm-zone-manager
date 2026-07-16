package it.bluecube.osrmzonemanager.zone;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

/**
 * Maps {@link ZoneEntity} to {@link ZoneDTO}.
 *
 * <p>{@code status} is parsed defensively (unknown/invalid values map to {@code null}
 * rather than throwing), and {@code error} defaults to an empty string. {@code process}
 * and {@code message} aren't present on the entity and are attached separately via
 * {@link #toZoneDTO(ZoneEntity, String, String)}.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ZoneMapper {
    @Named("parseStatusSafe")
    static ZoneStatus parseStatusSafe(String status) {
        if (status == null) {
            return null;
        }
        try {
            return ZoneStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Named("emptyIfNull")
    static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    @Mapping(target = "status", source = "status", qualifiedByName = "parseStatusSafe")
    @Mapping(target = "error", source = "error", qualifiedByName = "emptyIfNull")
    @Mapping(target = "process", ignore = true)
    @Mapping(target = "message", ignore = true)
    ZoneDTO toDTO(ZoneEntity entity);

    /**
     * Maps the entity and attaches an optional status message (no process info).
     */
    default ZoneDTO toZoneDTO(ZoneEntity zone, String message) {
        return toZoneDTO(zone, message, null);
    }

    /**
     * Maps the entity and attaches an optional status message and process indicator.
     */
    default ZoneDTO toZoneDTO(ZoneEntity zone, String message, String process) {
        return toDTO(zone)
                .withMessage(message)
                .withProcess(process);
    }
}