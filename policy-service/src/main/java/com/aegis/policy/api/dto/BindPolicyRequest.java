package com.aegis.policy.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Binds a new policy, creating version 1 of its history.")
public record BindPolicyRequest(
        @Schema(example = "AUTO-0001") @NotBlank @Size(max = 32)
        String policyNumber,

        @Schema(example = "PERSONAL_AUTO") @NotBlank @Size(max = 32)
        String productCode,

        @Schema(description = "First day of cover, inclusive.", example = "2026-01-01") @NotNull
        LocalDate inceptionDate,

        @Schema(
                description = "First day no longer covered, EXCLUSIVE. Null for no scheduled expiry.",
                example = "2027-01-01")
        LocalDate expiryDate,

        @Schema(example = "Priya Raman") @NotBlank @Size(max = 200)
        String insuredName,

        @Schema(example = "TX-DALLAS") @NotBlank @Size(max = 32)
        String territory,

        @NotEmpty @Valid List<CoverageDto> coverages) {}
