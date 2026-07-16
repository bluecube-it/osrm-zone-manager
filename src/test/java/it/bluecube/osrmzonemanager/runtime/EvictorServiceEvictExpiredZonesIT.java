package it.bluecube.osrmzonemanager.runtime;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.osrmzonemanager.maps.MapsService;
import it.bluecube.osrmzonemanager.zone.ZoneEntity;
import it.bluecube.osrmzonemanager.zone.ZoneRepository;
import it.bluecube.osrmzonemanager.zone.ZoneStatus;
import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.assertj.core.api.Assertions;

class EvictorServiceEvictExpiredZonesIT extends BaseIT {

    @Autowired
    private EvictorService evictorService;

    @Autowired
    private ZoneRepository zoneRepository;

    @Autowired
    private OsrmZoneManagerConfig config;

    @MockitoBean
    private ProcessSupervisorService processSupervisorService;

    @MockitoBean
    private MapsService mapsService;

    @BeforeEach
    void setUp() throws Exception {
        Mockito.doReturn("/tmp/base.pbf").when(mapsService).ensureBasePbf();
    }

    @Test
    void shouldEvictZonePastTtl() throws Exception {
        String zoneId = "expiredzone123";
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId(zoneId)
                .status(ZoneStatus.ACTIVE.name())
                .lastAccess(Instant.now().minus(100, ChronoUnit.DAYS))
                .build();
        zoneRepository.save(zone);
        Path zoneDir = createZoneDir(zoneId);

        evictorService.evictExpiredZones();

        Assertions.assertThat(zoneRepository.existsById(zoneId)).isFalse();
        Assertions.assertThat(Files.exists(zoneDir)).isFalse();
        Mockito.verify(processSupervisorService).stopZone(zoneId);
    }

    @Test
    void shouldSkipZoneWithinTtl() {
        String zoneId = "freshzone12345";
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId(zoneId)
                .status(ZoneStatus.ACTIVE.name())
                .lastAccess(Instant.now())
                .build();
        zoneRepository.save(zone);

        evictorService.evictExpiredZones();

        Assertions.assertThat(zoneRepository.existsById(zoneId)).isTrue();
        Mockito.verifyNoInteractions(processSupervisorService);
    }

    @Test
    void shouldSkipZoneWithNullLastAccess() {
        String zoneId = "nolastaccess12";
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId(zoneId)
                .status(ZoneStatus.ACTIVE.name())
                .lastAccess(null)
                .build();
        zoneRepository.save(zone);

        evictorService.evictExpiredZones();

        Assertions.assertThat(zoneRepository.existsById(zoneId)).isTrue();
        Mockito.verifyNoInteractions(processSupervisorService);
    }

    private Path createZoneDir(String zoneId) throws Exception {
        Path zoneDir = Path.of(config.getZonesDir(), zoneId);
        Files.createDirectories(zoneDir);
        Files.writeString(zoneDir.resolve("file.txt"), "data");
        return zoneDir;
    }
}
