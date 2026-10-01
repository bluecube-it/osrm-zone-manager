package it.bluecube.osrmzonemanager.global;

import it.bluecube.osrmzonemanager.zone.ZoneProfile;

/**
 * Internal tracking state of a global (whole base map) OSRM instance: the process, its port and
 * health bookkeeping. Mutable by {@link GlobalOsrmService} only.
 */
class GlobalOsrmInstance {

    final ZoneProfile profile;
    volatile GlobalOsrmStatus status;
    volatile Integer port;
    volatile Process osrm;
    volatile long osrmPid;
    volatile int retries;
    volatile String error;

    GlobalOsrmInstance(ZoneProfile profile) {
        this.profile = profile;
        this.status = GlobalOsrmStatus.DISABLED;
    }
}
