package it.bluecube.osrmzonemanager.builder;

public record BuildResult(
        String zoneId,
        boolean ok,
        Integer osrmPort,
        String error
) {
}
