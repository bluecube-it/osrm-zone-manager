package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.TestBuilders;
import it.bluecube.test.integration_test.BaseIT;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ZoneStateServiceFindRecoveryDataIT extends BaseIT {

    @Autowired
    private ZoneStateService zoneStateService;
    @Autowired
    private ZoneRepository zoneRepository;

    @Test
    void shouldReturnRecoveryDtoWithGeojsonAndHashWhenZoneExists() {
        ZoneEntity zone = TestBuilders.fullyPopulatedZoneEntity()
                .zoneId("recoveryzone1234")
                .polygonHash("polyhash123456")
                .polygonGeojson(TestBuilders.SAMPLE_POLYGON_GEOJSON)
                .lineStringsGeojson(TestBuilders.SAMPLE_LINE_STRINGS_GEOJSON)
                .build();
        zoneRepository.save(zone);

        var result = zoneStateService.findRecoveryData("recoveryzone1234");

        Assertions.assertThat(result).isPresent();
        Assertions.assertThat(result.get().zoneId()).isEqualTo("recoveryzone1234");
        Assertions.assertThat(result.get().polygonHash()).isEqualTo("polyhash123456");
        Assertions.assertThat(result.get().polygonGeojson()).isEqualTo(TestBuilders.SAMPLE_POLYGON_GEOJSON);
        Assertions.assertThat(result.get().lineStringsGeojson()).isEqualTo(TestBuilders.SAMPLE_LINE_STRINGS_GEOJSON);
    }

    @Test
    void shouldReturnEmptyWhenNotFound() {
        var result = zoneStateService.findRecoveryData("missing1234567");

        Assertions.assertThat(result).isEmpty();
    }
}
