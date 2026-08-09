package com.aegis.rating.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfiguration {

    /**
     * Used only to stamp when a quote was created. It is deliberately not available to the rating
     * engine: a premium that depends on the wall clock cannot be reproduced, and a premium that
     * cannot be reproduced cannot be explained.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
