package com.aegis.policy.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

@Schema(description = """
                Records a mid-term change. effectiveFrom may be in the past: a backdated
                endorsement applies from its effective date while remaining recorded as having
                been keyed in now, and it does not erase endorsements already recorded with a
                later effective date.
                """)
public record EndorsePolicyRequest(
        @Schema(description = "First day the new terms apply, inclusive. May be backdated.", example = "2026-04-01")
        @NotNull
        LocalDate effectiveFrom,

        @Schema(example = "Priya Raman") @NotBlank @Size(max = 200)
        String insuredName,

        @Schema(example = "TX-DALLAS") @NotBlank @Size(max = 32)
        String territory,

        @Schema(example = "Increased collision limit to 100,000") @Size(max = 500)
        String changeReason,

        @Schema(description = "The COMPLETE set of coverage lines from effectiveFrom onward, not a delta.")
        @NotEmpty
        @Valid
        List<CoverageDto> coverages) {}
