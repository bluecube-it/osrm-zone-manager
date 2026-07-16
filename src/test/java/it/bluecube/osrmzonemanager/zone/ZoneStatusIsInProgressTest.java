package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.BaseUnitTest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ZoneStatusIsInProgressTest extends BaseUnitTest {

    @Test
    void shouldReturnTrueForBuildingBuiltStarting() {
        Assertions.assertThat(ZoneStatus.BUILDING.isInProgress()).isTrue();
        Assertions.assertThat(ZoneStatus.BUILT.isInProgress()).isTrue();
        Assertions.assertThat(ZoneStatus.STARTING.isInProgress()).isTrue();
    }

    @Test
    void shouldReturnFalseForActiveDegradedFailed() {
        Assertions.assertThat(ZoneStatus.ACTIVE.isInProgress()).isFalse();
        Assertions.assertThat(ZoneStatus.DEGRADED.isInProgress()).isFalse();
        Assertions.assertThat(ZoneStatus.FAILED.isInProgress()).isFalse();
    }
}
