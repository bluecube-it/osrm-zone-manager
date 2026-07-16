package it.bluecube.osrmzonemanager.runtime;

/**
 * Internal process tracking state for a zone — holds running process references,
 * PIDs, and health status.
 */
class ProcessInfo {
    final String zoneId;
    final int osrmPort;
    final int vroomPort;
    Process osrm;
    Process vroom;
    long osrmPid;
    long vroomPid;
    int retries;
    volatile boolean healthy;

    ProcessInfo(String zoneId, int osrmPort, int vroomPort) {
        this.zoneId = zoneId;
        this.osrmPort = osrmPort;
        this.vroomPort = vroomPort;
        this.healthy = true;
    }
}
