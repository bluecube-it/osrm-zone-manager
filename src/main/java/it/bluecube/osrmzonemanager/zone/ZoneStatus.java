package it.bluecube.osrmzonemanager.zone;

import java.util.EnumSet;
import java.util.Set;

/**
 * Zone lifecycle states. Progression: BUILDING -> BUILT -> STARTING -> ACTIVE
 * (or DEGRADED from ACTIVE, FAILED from any).
 */
public enum ZoneStatus {
    BUILDING,
    BUILT,
    STARTING,
    ACTIVE,
    DEGRADED,
    FAILED;

    private static final Set<ZoneStatus> IN_PROGRESS = EnumSet.of(BUILDING, BUILT, STARTING);
    private static final Set<ZoneStatus> LIVE = EnumSet.of(ACTIVE, DEGRADED);
    private static final Set<ZoneStatus> RUNNING_OR_STARTING = EnumSet.of(ACTIVE, DEGRADED, STARTING);

    /**
     * Parses a raw status string, returning {@code null} for unknown/invalid values
     * instead of throwing — useful for defensively reading persisted data.
     */
    public static ZoneStatus parseSafe(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * True for statuses meaning "a build or start is currently underway".
     */
    public boolean isInProgress() {
        return IN_PROGRESS.contains(this);
    }

    /**
     * True for statuses meaning "the zone's processes are expected to be up".
     */
    public boolean isLive() {
        return LIVE.contains(this);
    }

    /**
     * True for statuses meaning "there may be a running process to stop".
     */
    public boolean isRunningOrStarting() {
        return RUNNING_OR_STARTING.contains(this);
    }
}
