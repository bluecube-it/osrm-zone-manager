package it.bluecube.osrmzonemanager;

import it.bluecube.test.BaseUnitTest;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class HashUtilsSha256Test extends BaseUnitTest {

    @Test
    void shouldProduceCorrectSha256ForKnownInput() {
        String input = "The quick brown fox jumps over the lazy dog";
        String expected = "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592";

        Assertions.assertThat(HashUtils.sha256(input.getBytes())).isEqualTo(expected);
    }

    @Test
    void shouldProduceKnownHashForEmptyInput() {
        Assertions.assertThat(HashUtils.sha256("".getBytes()))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void shouldBeDeterministicForSameInput() {
        byte[] data = "same input".getBytes();

        String h1 = HashUtils.sha256(data);
        String h2 = HashUtils.sha256(data);

        Assertions.assertThat(h1).isEqualTo(h2).hasSize(64);
    }
}
