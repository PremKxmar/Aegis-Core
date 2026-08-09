package com.aegis.rating.api.dto;

import com.aegis.contracts.money.Money;
import com.aegis.rating.domain.RatingInput;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "A risk to be rated. The effective date selects which rate table version applies.")
public record QuoteRequest(
        @Schema(example = "PERSONAL_AUTO") @NotBlank @Size(max = 32)
        String productCode,

        @Schema(
                description = "Rate AS OF this date. Not today's date - re-rating the same risk for the same "
                        + "effective date always reproduces the same premium.",
                example = "2026-06-01")
        @NotNull
        LocalDate effectiveDate,

        @Schema(example = "TX-DALLAS") @NotBlank @Size(max = 32)
        String territory,

        @Schema(description = "Age of the insured asset in whole years.", example = "3") @Min(0)
        int assetAgeYears,

        @Schema(description = "At-fault claims in the look-back period.", example = "1") @Min(0)
        int priorClaimsCount,

        @Schema(description = "Total policies the insured holds, including this one.", example = "2") @Min(1)
        int policiesHeld,

        @NotEmpty @Valid List<CoverageRequest> coverages) {

    public RatingInput toRatingInput() {
        return new RatingInput(
                productCode,
                effectiveDate,
                territory,
                assetAgeYears,
                priorClaimsCount,
                policiesHeld,
                coverages.stream()
                        .map(c -> new RatingInput.CoverageInput(c.coverageCode(), c.limit()))
                        .toList());
    }

    @Schema(description = "A coverage to rate, with the limit it should be rated at.")
    public record CoverageRequest(
            @Schema(example = "COLLISION") @NotBlank @Size(max = 32)
            String coverageCode,

            @Schema(description = "Base rate is charged per 1,000 of this.") @NotNull
            Money limit) {}
}
