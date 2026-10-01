package it.bluecube.osrmzonemanager.global;

/**
 * Lifecycle of a global (whole base map) OSRM instance, one per
 * {@link it.bluecube.osrmzonemanager.zone.ZoneProfile}.
 *
 * <p>Unlike zones these instances carry no persistence: they are derived from the base PBF and
 * rebuilt at boot when missing or stale.
 */
public enum GlobalOsrmStatus {
    /**
     * Global maps are disabled by configuration.
     */
    DISABLED,
    /**
     * Registered at boot, waiting for its turn in the sequential build queue.
     */
    PENDING,
    /**
     * The whole-map graph is being preprocessed ({@code osrm-extract/partition/customize}).
     */
    BUILDING,
    /**
     * The graph exists, {@code osrm-routed} is coming up.
     */
    STARTING,
    /**
     * {@code osrm-routed} is healthy and serving.
     */
    READY,
    /**
     * Health checks failed repeatedly; the instance still serves but is considered unreliable.
     */
    DEGRADED,
    /**
     * Build or start failed; the instance is not serving.
     */
    FAILED;

    /**
     * @return {@code true} when requests may be proxied to the instance
     */
    public boolean isLive() {
        return this == READY || this == DEGRADED;
    }
}
