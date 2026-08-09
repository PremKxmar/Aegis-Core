package com.aegis.policy.config;

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
    public OpenAPI policyServiceApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Aegis Core - Policy Service")
                        .version("1.0.0")
                        .description("""
                                Policy administration with bitemporal versioning.

                                A policy is an append-only chain of effective-dated versions. Every
                                version records both when its terms applied in the real world (valid
                                time: effectiveFrom/effectiveTo) and when this system believed them
                                (transaction time: recordedAt/supersededAt).

                                Both periods are half-open - lower bound inclusive, upper bound
                                exclusive - so adjacent versions never disagree about a boundary date.

                                The endpoint that matters is GET /coverage-verification, which
                                answers a claim's question: on the date of this loss, was this
                                coverage in force, and for how much?
                                """)
                        .contact(new Contact().name("Aegis Core"))
                        // OpenAPI 3.1 requires a license to carry either an SPDX identifier or a
                        // URL; a bare name is rejected by strict validators.
                        .license(new License().name("MIT").identifier("MIT")))
                // A relative server URL rather than the one springdoc infers from the request.
                // The inferred value embeds the host and port the document happened to be
                // fetched from — which makes the generated document differ on every run, and
                // bakes a test machine's random port into an artefact clients generate from.
                .servers(List.of(new Server().url("/").description("Relative to the deployed host")));
    }
}
