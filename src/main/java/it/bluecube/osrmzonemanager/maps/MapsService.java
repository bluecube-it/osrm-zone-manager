package it.bluecube.osrmzonemanager.maps;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class MapsService {
    private final OsrmZoneManagerConfig config;

    public String ensureBasePbf() {
        String pbf = config.getBasePbf();
        Path pbfPath = Path.of(pbf);

        if (isValidExistingPbf(pbfPath)) {
            log.info("Found base PBF: {} ({} MB)", pbf, sizeMbSafe(pbfPath));
            return pbf;
        }

        throw new MissingBasePbfException(pbf);
    }

    private boolean isValidExistingPbf(Path pbfPath) {
        try {
            return Files.exists(pbfPath) && Files.size(pbfPath) > config.getMinPbfSize();
        } catch (Exception e) {
            throw new IllegalStateException("Base PBF check failed: " + e.getMessage(), e);
        }
    }

    private String sizeMbSafe(Path path) {
        try {
            return String.format("%.1f", Files.size(path) / 1e6);
        } catch (IOException e) {
            return "?";
        }
    }
}
