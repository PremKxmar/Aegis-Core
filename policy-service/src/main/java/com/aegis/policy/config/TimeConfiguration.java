package com.aegis.policy.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfiguration {

    /**
     * The single source of "now" for the service.
     *
     * <p>Injected rather than called statically so that transaction-time instants are
     * substitutable in tests. UTC rather than the system default because a container scheduled
     * onto a node in a different timezone must not shift the instants that decide which version
     * of a policy the system believed at a point in time.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
