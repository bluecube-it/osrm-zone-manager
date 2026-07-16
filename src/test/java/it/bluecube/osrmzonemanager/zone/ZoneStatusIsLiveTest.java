package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.BaseUnitTest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ZoneStatusIsLiveTest extends BaseUnitTest {

    @Test
    void shouldReturnTrueForActiveDegraded() {
        Assertions.assertThat(ZoneStatus.ACTIVE.isLive()).isTrue();
        Assertions.assertThat(ZoneStatus.DEGRADED.isLive()).isTrue();
    }

    @Test
    void shouldReturnFalseForAllOthers() {
        Assertions.assertThat(ZoneStatus.BUILDING.isLive()).isFalse();
        Assertions.assertThat(ZoneStatus.BUILT.isLive()).isFalse();
        Assertions.assertThat(ZoneStatus.STARTING.isLive()).isFalse();
        Assertions.assertThat(ZoneStatus.FAILED.isLive()).isFalse();
        Assertions.assertThat(ZoneStatus.EVICTING.isLive()).isFalse();
    }
}
