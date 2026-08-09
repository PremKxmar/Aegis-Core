package com.aegis.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.aegis.policy.support.PostgresIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * Keeps the committed OpenAPI document honest.
 *
 * <p>The document in {@code src/main/resources/openapi/} is the artefact other teams read and
 * generate clients from, so it has to be in the repository rather than only served by a running
 * instance. But a committed copy of a generated thing rots the moment someone changes a
 * controller and forgets it — and a stale API document is worse than none, because people trust
 * it.
 *
 * <p>So the copy is not maintained by hand. This test regenerates it from the live application
 * and fails if it differs, with the command to refresh it. Run {@code make openapi} to accept
 * the change.
 */
class OpenApiSpecificationTest extends PostgresIntegrationTest {

    /** Relative to the module directory, which is the working directory of the Gradle test task. */
    private static final Path SPEC_PATH = Path.of("src/main/resources/openapi/policy-api.yaml");

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

        String committed = Files.readString(SPEC_PATH, StandardCharsets.UTF_8);
        assertThat(normalise(generated))
                .as("The committed OpenAPI document is out of date with the controllers. "
                        + "Run `make openapi` to regenerate it, and review the diff — if it changed "
                        + "in a way clients would notice, that is a breaking API change.")
                .isEqualTo(normalise(committed));
    }

    @Test
    void specificationDescribesTheEndpointsClaimsServiceDependsOn() {
        // A spec that parses but has quietly lost the endpoint another service calls is exactly
        // as broken as one that does not parse.
        String generated = rest.getForObject("/v3/api-docs.yaml", String.class);

        assertThat(generated)
                .contains("/api/v1/policies/{policyNumber}/coverage-verification")
                .contains("/api/v1/policies/{policyNumber}/endorsements")
                .contains("/api/v1/policies/{policyNumber}/cancellation")
                .contains("lossDate")
                .contains("coverageCode");
    }

    private static String normalise(String yaml) {
        // Line endings only; everything else must match exactly, including ordering.
        return yaml.replace("\r\n", "\n").strip();
    }
}
