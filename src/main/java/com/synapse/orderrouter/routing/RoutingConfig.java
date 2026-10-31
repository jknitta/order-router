package com.synapse.orderrouter.routing;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RoutingConfig {

    @Bean
    public SearchTimeout searchTimeout(@Value("${router.search.timeout.enabled}") boolean enabled,
            @Value("${router.search.timeout.duration}") Duration duration) {
        return new SearchTimeout(enabled, duration);
    }
}
