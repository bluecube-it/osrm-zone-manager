package it.bluecube.osrmzonemanager.runtime;

/**
 * Internal process tracking state for a zone — holds running process references,
 * PIDs, and health status.
 */
class ProcessInfo {
    final String zoneId;
    final int osrmPort;
    Process osrm;
    long osrmPid;
    int retries;
    volatile boolean healthy;

    ProcessInfo(String zoneId, int osrmPort) {
        this.zoneId = zoneId;
        this.osrmPort = osrmPort;
        this.healthy = true;
    }
}
