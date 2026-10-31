package com.synapse.orderrouter.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SearchTimeoutTest {

    @Test
    void aNegativeDurationIsRejectedAtStartup() {
        assertThatThrownBy(() -> new SearchTimeout(true, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aZeroDurationIsAllowed() {
        assertThat(new SearchTimeout(true, Duration.ZERO).duration()).isZero();
    }
}
