package com.aegis.policy.api.dto;

import com.aegis.contracts.money.Money;
import com.aegis.policy.domain.Coverage;
import com.aegis.policy.domain.PolicyVersion;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * The answer claims-service needs: on the date of this loss, was this coverage in force, and for
 * how much?
 *
 * <p>Returned with HTTP 200 whether or not the loss is covered. "Not covered" is a valid,
 * fully-formed answer to a well-formed question, and modelling it as an error status would force
 * the caller to parse error bodies to distinguish "no cover" from "policy service is broken" —
 * a distinction that decides whether a claim gets denied or retried.
 */
@Schema(description = "Coverage position for one coverage code on one date, resolved bitemporally.")
public record CoverageVerificationResponse(
        String policyNumber,

        @Schema(description = "The date the coverage question was asked about - for a claim, the loss date.")
        LocalDate asOfDate,

        @Schema(description = "The transaction-time instant this answer reflects.")
        Instant asAtInstant,

        @Schema(description = "Null when no version of the policy existed on asOfDate.")
        UUID versionId,

        Integer versionNumber,

        @Schema(description = "True only if a version existed AND it was not cancelled.")
        boolean policyInForce,

        @Schema(description = "True if that version carried a line for the requested coverage code.")
        boolean coverageFound,

        String coverageCode,
        Money limit,
        Money deductible,
        Set<String> exclusions,

        @Schema(description = "Human-readable reason, always populated - it is quoted verbatim in claim decisions.")
        String explanation) {

    /** No version of the policy existed on that date. */
    public static CoverageVerificationResponse noPolicyVersion(
            String policyNumber, LocalDate asOf, Instant asAt, String coverageCode) {
        return new CoverageVerificationResponse(
                policyNumber,
                asOf,
                asAt,
                null,
                null,
                false,
                false,
                coverageCode,
                null,
                null,
                Set.of(),
                "No version of policy " + policyNumber + " was in force on " + asOf);
    }

    public static CoverageVerificationResponse from(
            PolicyVersion version, String coverageCode, LocalDate asOf, Instant asAt) {
        String policyNumber = version.policy().policyNumber();

        if (!version.isInForce()) {
            return new CoverageVerificationResponse(
                    policyNumber,
                    asOf,
                    asAt,
                    version.id(),
                    version.versionNumber(),
                    false,
                    false,
                    coverageCode,
                    null,
                    null,
                    Set.of(),
                    "Policy " + policyNumber + " was cancelled with effect from " + version.effectiveFrom());
        }

        return version.coverage(coverageCode)
                .map(coverage -> covered(version, coverage, asOf, asAt))
                .orElseGet(() -> new CoverageVerificationResponse(
                        policyNumber,
                        asOf,
                        asAt,
                        version.id(),
                        version.versionNumber(),
                        true,
                        false,
                        coverageCode,
                        null,
                        null,
                        Set.of(),
                        "Policy " + policyNumber + " was in force on " + asOf + " but carried no " + coverageCode
                                + " coverage on version " + version.versionNumber()));
    }

    private static CoverageVerificationResponse covered(
            PolicyVersion version, Coverage coverage, LocalDate asOf, Instant asAt) {
        return new CoverageVerificationResponse(
                version.policy().policyNumber(),
                asOf,
                asAt,
                version.id(),
                version.versionNumber(),
                true,
                true,
                coverage.coverageCode(),
                coverage.limit(),
                coverage.deductible(),
                coverage.exclusions(),
                "Covered under version " + version.versionNumber() + ", effective from " + version.effectiveFrom()
                        + ", limit " + coverage.limit() + " with deductible " + coverage.deductible());
    }
}
