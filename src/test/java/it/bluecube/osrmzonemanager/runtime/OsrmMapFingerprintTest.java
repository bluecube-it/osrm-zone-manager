package it.bluecube.osrmzonemanager.runtime;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.stream.Stream;

class OsrmMapFingerprintTest {

    private final OsrmMapFingerprint fingerprint = new OsrmMapFingerprint();

    @Test
    void shouldReportIncompleteMapWhenAnArtifactIsMissing() throws Exception {
        Path dir = Files.createTempDirectory("fingerprint");
        OsrmMapFingerprint.REQUIRED_MAP_FILES.forEach(file -> write(dir, file));

        Assertions.assertThat(fingerprint.isMapComplete(dir)).isTrue();

        Files.delete(dir.resolve("map.osrm.mldgr"));

        Assertions.assertThat(fingerprint.isMapComplete(dir)).isFalse();
        Assertions.assertThat(fingerprint.isUsable(dir, "12345")).isFalse();
        deleteRecursively(dir);
    }

    @Test
    void shouldMatchOnlyWithTheSameOsrmVersionAndDataset() throws Exception {
        Path dir = Files.createTempDirectory("fingerprint");
        OsrmMapFingerprint.REQUIRED_MAP_FILES.forEach(file -> write(dir, file));
        fingerprint.write(dir, "12345");

        Assertions.assertThat(fingerprint.isUsable(dir, "12345")).isTrue();
        Assertions.assertThat(fingerprint.matches(dir, "99999")).isFalse();
        Assertions.assertThat(fingerprint.isUsable(dir, "99999")).isFalse();
        deleteRecursively(dir);
    }

    @Test
    void shouldTreatMapWithoutMarkerAsStale() throws Exception {
        Path dir = Files.createTempDirectory("fingerprint");
        OsrmMapFingerprint.REQUIRED_MAP_FILES.forEach(file -> write(dir, file));

        Assertions.assertThat(fingerprint.isMapComplete(dir)).isTrue();
        Assertions.assertThat(fingerprint.matches(dir, "12345")).isFalse();
        deleteRecursively(dir);
    }

    @Test
    void shouldInvalidateMarkerWhenTheInstalledOsrmVersionChanges() throws Exception {
        Path dir = Files.createTempDirectory("fingerprint");
        OsrmMapFingerprint.REQUIRED_MAP_FILES.forEach(file -> write(dir, file));
        fingerprint.write(dir, "12345");

        String marker = Files.readString(dir.resolve(OsrmMapFingerprint.MARKER_FILE));
        // simulate a map produced by another OSRM release
        Files.writeString(dir.resolve(OsrmMapFingerprint.MARKER_FILE),
                marker.replaceFirst("osrm-version=.*", "osrm-version=osrm-extract 26.4"));

        Assertions.assertThat(fingerprint.matches(dir, "12345")).isFalse();
        deleteRecursively(dir);
    }

    @Test
    void shouldFingerprintPbfByLastModifiedTime() throws Exception {
        Path pbf = Files.createTempFile("base", ".osm.pbf");
        Files.setLastModifiedTime(pbf, FileTime.fromMillis(1_700_000_000_000L));

        Assertions.assertThat(fingerprint.pbfFingerprint(pbf)).isEqualTo("1700000000000");
        Assertions.assertThat(fingerprint.pbfFingerprint(pbf.resolveSibling("missing.pbf"))).isEqualTo("unknown");
        Files.delete(pbf);
    }

    private void write(Path dir, String name) {
        try {
            Files.writeString(dir.resolve(name), "");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // temp dir cleanup
                }
            });
        }
    }
}
