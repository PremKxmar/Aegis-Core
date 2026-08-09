package com.aegis.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.aegis.rating.support.PostgresIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * Fails when the committed OpenAPI document drifts from the controllers. Run {@code make openapi}
 * to refresh it. See policy-service's equivalent for the reasoning.
 */
class OpenApiSpecificationTest extends PostgresIntegrationTest {

    private static final Path SPEC_PATH = Path.of("src/main/resources/openapi/rating-api.yaml");
    private static final String UPDATE_FLAG = "updateOpenApi";

    @Autowired
    private TestRestTemplate rest;

    @Test
    void committedSpecificationMatchesTheRunningApplication() throws IOException {
        String generated = rest.getForObject("/v3/api-docs.yaml", String.class);
        assertThat(generated).as("springdoc served no document").isNotBlank();

        if (Boolean.getBoolean(UPDATE_FLAG)) {
            Files.createDirectories(SPEC_PATH.getParent());
            Files.writeString(SPEC_PATH, generated, StandardCharsets.UTF_8);
            return;
        }

        if (!Files.exists(SPEC_PATH)) {
            fail("No committed OpenAPI document at %s. Run `make openapi` to generate it."
                    .formatted(SPEC_PATH.toAbsolutePath()));
        }

        assertThat(normalise(generated))
                .as("The committed OpenAPI document is out of date with the controllers. "
                        + "Run `make openapi` to regenerate it, and review the diff.")
                .isEqualTo(normalise(Files.readString(SPEC_PATH, StandardCharsets.UTF_8)));
    }

    @Test
    void specificationDescribesTheWorksheetShape() {
        // The worksheet is the reason this service is interesting; a spec that omits it is
        // describing a different API from the one that exists.
        String generated = rest.getForObject("/v3/api-docs.yaml", String.class);

        assertThat(generated)
                .contains("/api/v1/quotes")
                .contains("/api/v1/rate-tables")
                .contains("WorksheetLineResponse")
                .contains("factorValue")
                .contains("subtotal");
    }

    private static String normalise(String yaml) {
        return yaml.replace("\r\n", "\n").strip();
    }
}
