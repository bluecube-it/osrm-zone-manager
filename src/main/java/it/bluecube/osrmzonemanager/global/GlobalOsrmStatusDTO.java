package it.bluecube.osrmzonemanager.global;

/**
 * Transport view of a global whole-map OSRM instance, exposed by {@code GET /osrm}.
 *
 * @param profile routing profile name ({@code CAR}, {@code BUS})
 * @param status  lifecycle status ({@link GlobalOsrmStatus#name()})
 * @param port    loopback port the instance listens on, {@code null} while building
 * @param error   failure reason, {@code null} when healthy
 */
public record GlobalOsrmStatusDTO(String profile, String status, Integer port, String error) {
}
