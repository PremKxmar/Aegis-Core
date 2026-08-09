package com.aegis.rating.api.dto;

import com.aegis.contracts.money.Money;
import com.aegis.rating.domain.Quote;
import com.aegis.rating.domain.QuoteCoverage;
import com.aegis.rating.domain.QuoteWorksheetLine;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Schema(description = """
                A premium and the arithmetic that produced it. The worksheet is the point: every
                factor applied, with its input, its multiplier and the running subtotal, so any
                premium can be explained line by line.
                """)
public record QuoteResponse(
        String quoteNumber,
        String productCode,

        @Schema(description = "The date the risk was rated as of.")
        LocalDate effectiveDate,

        @Schema(description = "Which published rate table produced this premium.")
        String rateTable,

        int rateTableVersion,
        String territory,
        int assetAgeYears,
        int priorClaimsCount,
        int policiesHeld,
        List<CoverageQuoted> coverages,

        @Schema(description = "Always equal to the subtotal on the last worksheet line.")
        Money totalPremium,

        List<WorksheetLineResponse> worksheet,
        Instant createdAt) {

    public static QuoteResponse from(Quote quote) {
        return new QuoteResponse(
                quote.quoteNumber(),
                quote.productCode(),
                quote.effectiveDate(),
                quote.rateTable().describe(),
                quote.rateTableVersion(),
                quote.territory(),
                quote.assetAgeYears(),
                quote.priorClaimsCount(),
                quote.policiesHeld(),
                quote.coverages().stream()
                        .sorted(Comparator.comparing(QuoteCoverage::coverageCode))
                        .map(c -> new CoverageQuoted(c.coverageCode(), c.limit()))
                        .toList(),
                quote.totalPremium(),
                quote.worksheet().stream()
                        .map(QuoteWorksheetLine::toWorksheetLine)
                        .map(WorksheetLineResponse::from)
                        .toList(),
                quote.createdAt());
    }

    public record CoverageQuoted(String coverageCode, Money limit) {}

    @Schema(description = "One step of the calculation.")
    public record WorksheetLineResponse(
            int stepNumber,
            String stepType,
            String description,

            @Schema(description = "What went in, as displayed text.")
            String inputValue,

            @Schema(description = "The multiplier applied. Null on additive base-rate lines.")
            BigDecimal factorValue,

            @Schema(description = "The running total after this step.")
            Money subtotal) {

        static WorksheetLineResponse from(com.aegis.rating.domain.WorksheetLine line) {
            return new WorksheetLineResponse(
                    line.stepNumber(),
                    line.type().name(),
                    line.description(),
                    line.inputValue(),
                    line.factorValue(),
                    line.subtotal());
        }
    }
}
