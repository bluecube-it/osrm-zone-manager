package it.bluecube.osrmzonemanager.vroom;

/**
 * Resolved per-request VROOM solving options, after applying the client
 * {@code options} overrides allowed by {@code osrm.zone-manager.vroom-override}.
 *
 * @param threads      number of solving threads ({@code -t})
 * @param explore      exploration level, 0..5 ({@code -x})
 * @param geometry     whether to return route geometry ({@code -g})
 * @param chooseEta    choose ETA for custom routes and report violations ({@code -c})
 * @param limitSeconds solving time limit in seconds ({@code -l}); 0 means "no limit flag"
 */
public record VroomOptions(
        int threads,
        int explore,
        boolean geometry,
        boolean chooseEta,
        int limitSeconds
) {
}
