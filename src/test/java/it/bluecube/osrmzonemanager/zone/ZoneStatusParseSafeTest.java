package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.BaseUnitTest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ZoneStatusParseSafeTest extends BaseUnitTest {

    @Test
    void shouldReturnEnumForValidString() {
        Assertions.assertThat(ZoneStatus.parseSafe("BUILDING")).isEqualTo(ZoneStatus.BUILDING);
        Assertions.assertThat(ZoneStatus.parseSafe("ACTIVE")).isEqualTo(ZoneStatus.ACTIVE);
    }

    @Test
    void shouldReturnNullForNull() {
        Assertions.assertThat(ZoneStatus.parseSafe(null)).isNull();
    }

    @Test
    void shouldReturnNullForInvalidString() {
        Assertions.assertThat(ZoneStatus.parseSafe("UNKNOWN")).isNull();
    }
}
