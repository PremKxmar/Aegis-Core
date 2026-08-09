package com.aegis.policy.api.dto;

import com.aegis.policy.domain.Coverage;
import com.aegis.policy.domain.PolicyVersion;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Schema(description = """
                One version of a policy, positioned in both time dimensions.
                effectiveTo and supersededAt are EXCLUSIVE upper bounds; null means unbounded.
                """)
public record PolicyVersionResponse(
        UUID versionId,
        String policyNumber,
        int versionNumber,
        String changeType,
        String status,

        @Schema(description = "Valid time: first day these terms applied, inclusive.")
        LocalDate effectiveFrom,

        @Schema(description = "Valid time: first day they no longer applied, EXCLUSIVE.")
        LocalDate effectiveTo,

        @Schema(description = "Transaction time: when the system recorded these terms.")
        Instant recordedAt,

        @Schema(description = "Transaction time: when the system stopped believing them, EXCLUSIVE. Null if current.")
        Instant supersededAt,

        String insuredName,
        String territory,
        String changeReason,
        List<CoverageDto> coverages) {

    public static PolicyVersionResponse from(PolicyVersion version) {
        return new PolicyVersionResponse(
                version.id(),
                version.policy().policyNumber(),
                version.versionNumber(),
                version.changeType().name(),
                version.status().name(),
                version.effectiveFrom(),
                version.effectiveTo(),
                version.recordedAt(),
                version.supersededAt(),
                version.insuredName(),
                version.territory(),
                version.changeReason(),
                version.coverages().stream()
                        // Sorted so the response is byte-stable for a given version: an
                        // arbitrary collection order would make responses differ between calls
                        // and break both caching and the response-diffing in the contract tests.
                        .sorted(Comparator.comparing(Coverage::coverageCode))
                        .map(c -> new CoverageDto(c.coverageCode(), c.limit(), c.deductible(), c.exclusions()))
                        .toList());
    }
}
