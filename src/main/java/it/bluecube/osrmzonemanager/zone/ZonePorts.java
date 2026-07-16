package it.bluecube.osrmzonemanager.zone;

/**
 * Data holder for osrm + vroom port pair, returned by
 * {@link ZoneStateService#findPorts} to avoid entity leak.
 */
public record ZonePorts(int osrmPort, int vroomPort) {
}
