package com.aegis.rating.api.dto;

import com.aegis.contracts.money.Money;
import com.aegis.rating.domain.RateAgeBand;
import com.aegis.rating.domain.RateBase;
import com.aegis.rating.domain.RateClaimsBand;
import com.aegis.rating.domain.RateTable;
import com.aegis.rating.domain.RateTerritory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Schema(description = "A published rate table. Effective-dated; never edited after publication.")
public record RateTableResponse(
        UUID rateTableId,
        String productCode,
        int tableVersion,

        @Schema(description = "First day these rates apply, inclusive.")
        LocalDate effectiveFrom,

        @Schema(description = "First day they no longer apply, EXCLUSIVE. Null if current.")
        LocalDate effectiveTo,

        Instant publishedAt,
        BigDecimal multiPolicyDiscountFactor,
        Money minimumPremium,
        List<BaseRateResponse> baseRates,
        List<TerritoryFactorResponse> territoryFactors,
        List<BandResponse> assetAgeBands,
        List<BandResponse> claimsBands) {

    public static RateTableResponse from(RateTable table) {
        return new RateTableResponse(
                table.id(),
                table.productCode(),
                table.tableVersion(),
                table.effectiveFrom(),
                table.effectiveTo(),
                table.publishedAt(),
                table.multiPolicyDiscountFactor(),
                table.minimumPremium(),
                table.baseRates().stream()
                        .sorted(Comparator.comparing(RateBase::coverageCode))
                        .map(r -> new BaseRateResponse(r.coverageCode(), r.ratePerThousand()))
                        .toList(),
                table.territoryFactors().stream()
                        .sorted(Comparator.comparing(RateTerritory::territory))
                        .map(t -> new TerritoryFactorResponse(t.territory(), t.factor()))
                        .toList(),
                table.ageBands().stream()
                        .sorted(Comparator.comparingInt(RateAgeBand::minAgeYears))
                        .map(b -> new BandResponse(b.minAgeYears(), b.maxAgeYears(), b.factor()))
                        .toList(),
                table.claimsBands().stream()
                        .sorted(Comparator.comparingInt(RateClaimsBand::minClaims))
                        .map(b -> new BandResponse(b.minClaims(), b.maxClaims(), b.factor()))
                        .toList());
    }

    public record BaseRateResponse(String coverageCode, BigDecimal ratePerThousand) {}

    public record TerritoryFactorResponse(String territory, BigDecimal factor) {}

    @Schema(description = "A half-open band: min <= value < max. Null max means open-ended.")
    public record BandResponse(int min, Integer max, BigDecimal factor) {}
}
