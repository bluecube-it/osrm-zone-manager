package it.bluecube.osrmzonemanager.maps;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class MapsService {
    private static final Duration PROGRESS_LOG_INTERVAL = Duration.ofSeconds(5);
    private static final int CHUNK_SIZE = 1_048_576;

    private final OsrmZoneManagerConfig config;
    private final RestClient mapsRestClient;

    public String ensureBasePbf() throws IOException {
        String pbf = config.getBasePbf();
        Path pbfPath = Path.of(pbf);

        if (isValidExistingPbf(pbfPath)) {
            log.info("Found base PBF: {} ({} MB)", pbf, sizeMb(pbfPath));
            return pbf;
        }

        return downloadBasePbf(pbf, pbfPath);
    }

    private boolean isValidExistingPbf(Path pbfPath) {
        try {
            Files.createDirectories(pbfPath.getParent());
            return Files.exists(pbfPath) && Files.size(pbfPath) > config.getMinPbfSize();
        } catch (Exception e) {
            throw new IllegalStateException("Base PBF check failed: " + e.getMessage(), e);
        }
    }

    private String downloadBasePbf(String pbf, Path pbfPath) {
        log.info("Downloading base PBF from {} → {}", config.getGeofabrikUrl(), pbf);
        Path temporaryPbfPath = Path.of("%s.tmp".formatted(pbf));

        try {
            fetchToFile(config.getGeofabrikUrl(), temporaryPbfPath);
            validateDownloadedFile(temporaryPbfPath);
            Files.move(temporaryPbfPath, pbfPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Base PBF downloaded: {} ({} MB)", pbf, sizeMb(pbfPath));
            return pbf;
        } catch (Exception e) {
            deleteQuietly(temporaryPbfPath);
            throw new IllegalStateException("Base PBF download failed: " + e.getMessage(), e);
        }
    }

    private void fetchToFile(String url, Path destination) {
        mapsRestClient.get()
                .uri(url)
                .exchange((req, res) -> {
                    long total = res.getHeaders().getContentLength();
                    try (InputStream is = res.getBody();
                         FileOutputStream fos = new FileOutputStream(destination.toFile())) {
                        streamWithProgress(is, fos, total);
                    }
                    return null;
                });
    }

    private void streamWithProgress(InputStream is, FileOutputStream fos, long total) throws IOException {
        byte[] buffer = new byte[CHUNK_SIZE];
        long downloaded = 0;
        long lastLog = 0;
        int read;

        while ((read = is.read(buffer)) >= 0) {
            fos.write(buffer, 0, read);
            downloaded += read;

            long now = System.currentTimeMillis();
            if (now - lastLog > PROGRESS_LOG_INTERVAL.toMillis()) {
                logProgress(downloaded, total);
                lastLog = now;
            }
        }
    }

    private void logProgress(long downloaded, long total) {
        if (total > 0) {
            log.info("Downloading: {} MB / {} MB ({}%)",
                    String.format("%.1f", downloaded / 1e6),
                    String.format("%.1f", total / 1e6),
                    Math.round(downloaded * 100.0 / total));
        } else {
            log.info("Downloading: {} MB", String.format("%.1f", downloaded / 1e6));
        }
    }

    private void validateDownloadedFile(Path temporaryPbfPath) throws IOException {
        if (!Files.exists(temporaryPbfPath) || Files.size(temporaryPbfPath) <= config.getMinPbfSize()) {
            Files.deleteIfExists(temporaryPbfPath);
            throw new IllegalStateException("Base PBF incomplete or too small");
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception _) {
            // nothing to do
        }
    }

    private String sizeMb(Path path) throws IOException {
        return String.format("%.1f", Files.size(path) / 1e6);
    }
}