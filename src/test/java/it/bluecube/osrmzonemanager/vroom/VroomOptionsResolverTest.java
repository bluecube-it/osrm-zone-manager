package it.bluecube.osrmzonemanager.vroom;

import it.bluecube.osrmzonemanager.OsrmZoneManagerConfig;
import it.bluecube.test.BaseUnitTest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

class VroomOptionsResolverTest extends BaseUnitTest {

    private OsrmZoneManagerConfig config;
    private VroomOptionsResolver resolver;

    @BeforeEach
    void setUp() {
        config = Mockito.mock(OsrmZoneManagerConfig.class);
        Mockito.lenient().when(config.getVroomThreads()).thenReturn(4);
        Mockito.lenient().when(config.getVroomExplore()).thenReturn(5);
        Mockito.lenient().when(config.isVroomGeometry()).thenReturn(false);
        Mockito.lenient().when(config.isVroomChooseEta()).thenReturn(false);
        Mockito.lenient().when(config.getVroomLimitSeconds()).thenReturn(0);
        Mockito.lenient().when(config.getVroomOverride()).thenReturn(List.of("c", "g", "l", "t", "x"));
        resolver = new VroomOptionsResolver(config);
    }

    @Test
    void usesConfiguredDefaultsWhenNoOptionsGiven() {
        VroomOptions options = resolver.resolve(json("{\"jobs\":[],\"vehicles\":[]}"));

        Assertions.assertThat(options).isEqualTo(new VroomOptions(4, 5, false, false, 0));
    }

    @Test
    void appliesAllowedOverrides() {
        VroomOptions options = resolver.resolve(json("""
                {"jobs":[],"vehicles":[],"options":{"t":8,"x":2,"g":true,"c":true,"l":30}}"""));

        Assertions.assertThat(options).isEqualTo(new VroomOptions(8, 2, true, true, 30));
    }

    @Test
    void ignoresOptionsNotInAllowList() {
        Mockito.when(config.getVroomOverride()).thenReturn(List.of("g"));

        VroomOptions options = resolver.resolve(json("""
                {"jobs":[],"vehicles":[],"options":{"t":8,"x":2,"c":true,"l":30,"g":true}}"""));

        Assertions.assertThat(options).isEqualTo(new VroomOptions(4, 5, true, false, 0));
    }

    @Test
    void ignoresNonNumericValuesForNumericOptions() {
        VroomOptions options = resolver.resolve(json("""
                {"jobs":[],"vehicles":[],"options":{"t":"many","x":null,"l":"10"}}"""));

        Assertions.assertThat(options).isEqualTo(new VroomOptions(4, 5, false, false, 0));
    }

    @Test
    void ignoresNonBooleanValuesForBooleanOptions() {
        VroomOptions options = resolver.resolve(json("""
                {"jobs":[],"vehicles":[],"options":{"g":"true","c":1}}"""));

        Assertions.assertThat(options).isEqualTo(new VroomOptions(4, 5, false, false, 0));
    }

    @Test
    void ignoresNullBooleanOptions() {
        VroomOptions options = resolver.resolve(json("""
                {"jobs":[],"vehicles":[],"options":{"g":null,"c":null}}"""));

        Assertions.assertThat(options).isEqualTo(new VroomOptions(4, 5, false, false, 0));
    }

    @Test
    void negativeValuesFallBackToDefaults() {
        VroomOptions options = resolver.resolve(json("""
                {"jobs":[],"vehicles":[],"options":{"t":-3,"x":-1,"l":-5}}"""));

        Assertions.assertThat(options).isEqualTo(new VroomOptions(4, 5, false, false, 0));
    }

    @Test
    void nullOverrideListMeansNoOverridesAllowed() {
        Mockito.when(config.getVroomOverride()).thenReturn(null);

        VroomOptions options = resolver.resolve(json("""
                {"jobs":[],"vehicles":[],"options":{"g":true,"t":8}}"""));

        Assertions.assertThat(options).isEqualTo(new VroomOptions(4, 5, false, false, 0));
    }

    @Test
    void overrideKeysAreCaseInsensitive() {
        Mockito.when(config.getVroomOverride()).thenReturn(List.of(" G ", "T"));

        VroomOptions options = resolver.resolve(json("""
                {"jobs":[],"vehicles":[],"options":{"g":true,"t":2}}"""));

        Assertions.assertThat(options.geometry()).isTrue();
        Assertions.assertThat(options.threads()).isEqualTo(2);
    }

    private JsonNode json(String raw) {
        return JsonMapper.builder().build().readTree(raw);
    }
}
