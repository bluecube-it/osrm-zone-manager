package it.bluecube.osrmzonemanager.zone;

import it.bluecube.test.BaseUnitTest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class ZoneProfileTest extends BaseUnitTest {

    @Test
    void shouldParseValueCaseInsensitively() {
        Assertions.assertThat(ZoneProfile.fromValue("CAR")).isEqualTo(ZoneProfile.CAR);
        Assertions.assertThat(ZoneProfile.fromValue("bus")).isEqualTo(ZoneProfile.BUS);
        Assertions.assertThat(ZoneProfile.fromValue("  Bus  ")).isEqualTo(ZoneProfile.BUS);
    }

    @Test
    void shouldReturnNullWhenValueIsNull() {
        Assertions.assertThat(ZoneProfile.fromValue(null)).isNull();
    }

    @Test
    void shouldRejectUnknownValue() {
        Assertions.assertThatThrownBy(() -> ZoneProfile.fromValue("foot"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported profile 'foot'")
                .hasMessageContaining("CAR, BUS");
    }

    @Test
    void shouldListSupportedValues() {
        Assertions.assertThat(ZoneProfile.supportedValues()).isEqualTo("CAR, BUS");
    }
}
