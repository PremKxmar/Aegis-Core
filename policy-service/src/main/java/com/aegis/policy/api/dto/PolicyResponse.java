package com.aegis.policy.api.dto;

import com.aegis.policy.domain.Policy;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A policy and the timeline of versions the system currently believes.")
public record PolicyResponse(
        UUID policyId,
        String policyNumber,
        String productCode,
        Instant createdAt,

        @Schema(description = "Optimistic lock value; supply it as If-Match on writes to detect lost updates.")
        long lockVersion,

        @Schema(description = "The believed timeline in effective-date order, gap-free and non-overlapping.")
        List<PolicyVersionResponse> currentChain) {

    public static PolicyResponse from(Policy policy) {
        return new PolicyResponse(
                policy.id(),
                policy.policyNumber(),
                policy.productCode(),
                policy.createdAt(),
                policy.lockVersion(),
                policy.currentChain().stream().map(PolicyVersionResponse::from).toList());
    }
}
