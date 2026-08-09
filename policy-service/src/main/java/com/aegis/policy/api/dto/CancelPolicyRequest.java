package com.aegis.policy.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

@Schema(description = "Takes the policy off risk from effectiveFrom onward. Terminal.")
public record CancelPolicyRequest(
        @Schema(description = "First day off risk, inclusive. May be backdated.", example = "2026-09-01") @NotNull
        LocalDate effectiveFrom,

        @Schema(example = "Non-payment of premium") @Size(max = 500)
        String reason) {}
