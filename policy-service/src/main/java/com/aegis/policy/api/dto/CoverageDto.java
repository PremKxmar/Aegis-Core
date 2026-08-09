package com.aegis.policy.api.dto;

import com.aegis.contracts.money.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

/** One coverage line, on the way in and on the way out. */
@Schema(description = "A single coverage line: what is covered, to what limit, above what deductible.")
public record CoverageDto(
        @Schema(example = "COLLISION") @NotBlank @Size(max = 32)
        String coverageCode,

        @Schema(description = "Maximum payable for a single loss under this coverage.") @NotNull
        Money limit,

        @Schema(description = "Deducted from the loss before payment.") @NotNull
        Money deductible,

        @Schema(description = "Exclusion codes carved out of this coverage.", example = "[\"RACING\"]")
        Set<String> exclusions) {

    public CoverageDto {
        // Normalising here rather than trusting the caller keeps the response shape stable:
        // a null and an empty exclusion set mean the same thing and should serialise the same.
        exclusions = exclusions == null ? Set.of() : Set.copyOf(exclusions);
    }
}
