package com.synapse.orderrouter.routing;

import java.time.Duration;

/** How long the supplier search may run for one order before it gives up. Switchable by configuration. */
public record SearchTimeout(boolean enabled, Duration duration) {

    public SearchTimeout {
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("The search timeout must not be negative.");
        }
    }

    public static SearchTimeout disabled() {
        return new SearchTimeout(false, Duration.ofSeconds(2));
    }
}
