package domi.argenticpptmaster.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PptAutoPropertiesTests {

    @Test
    void defaultsImageRetryRoundsToTwo() {
        assertThat(PptAutoProperties.defaults().resolvedImageRetryRounds()).isEqualTo(2);
        assertThat(new PptAutoProperties(null).resolvedImageRetryRounds()).isEqualTo(2);
    }

    @Test
    void acceptsZeroRounds() {
        assertThat(new PptAutoProperties(0).resolvedImageRetryRounds()).isZero();
    }

    @Test
    void rejectsNegativeRounds() {
        assertThatThrownBy(() -> new PptAutoProperties(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("image-retry-rounds");
    }
}
