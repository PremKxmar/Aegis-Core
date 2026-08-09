package com.aegis.rating.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI ratingServiceApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Aegis Core - Rating Service")
                        .version("1.0.0")
                        .description("""
                                Deterministic premium calculation from versioned, effective-dated rate tables.

                                Same input plus same rate table version always produces the same premium.
                                The engine is a pure function: no clock, no randomness, no ambient state,
                                and the effective date is a parameter rather than "today".

                                Every quote stores its worksheet - the ordered list of factors applied,
                                each with its input, factor value and running subtotal - so any premium
                                can be explained line by line rather than merely reproduced.
                                """)
                        .contact(new Contact().name("Aegis Core"))
                        .license(new License().name("MIT").identifier("MIT")))
                .servers(List.of(new Server().url("/").description("Relative to the deployed host")));
    }
}
