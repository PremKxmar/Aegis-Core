package com.aegis.rating.api;

import com.aegis.rating.api.dto.QuoteRequest;
import com.aegis.rating.api.dto.QuoteResponse;
import com.aegis.rating.api.dto.RateTableResponse;
import com.aegis.rating.application.RatingApplicationService;
import com.aegis.rating.domain.Quote;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Rating", description = "Deterministic premium calculation with an explainable worksheet")
public class RatingController {

    private final RatingApplicationService rating;

    public RatingController(RatingApplicationService rating) {
        this.rating = rating;
    }

    @PostMapping("/quotes")
    @Operation(summary = "Rate a risk and issue a quote", description = """
                    Selects the rate table in force on the risk's effective date - never simply
                    the newest - runs the rules in a fixed order and stores the worksheet.
                    Rating the same risk for the same effective date always produces the same
                    premium, however long afterwards.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Quoted"),
        @ApiResponse(responseCode = "400", description = "Request failed validation"),
        @ApiResponse(
                responseCode = "422",
                description = "The risk cannot be rated: no table in force, or an unrated coverage, "
                        + "territory, age or claim count"),
    })
    public ResponseEntity<QuoteResponse> quote(@Valid @RequestBody QuoteRequest request) {
        Quote quote = rating.quote(request.toRatingInput());

        return ResponseEntity.created(URI.create("/api/v1/quotes/" + quote.quoteNumber()))
                .body(QuoteResponse.from(quote));
    }

    @GetMapping("/quotes/{quoteNumber}")
    @Operation(
            summary = "Retrieve a quote and its worksheet",
            description = "Returns the worksheet as it was stored, not as it would be recomputed today.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The quote"),
        @ApiResponse(responseCode = "404", description = "No such quote"),
    })
    public QuoteResponse findQuote(@PathVariable String quoteNumber) {
        return QuoteResponse.from(rating.findByQuoteNumber(quoteNumber));
    }

    @GetMapping("/rate-tables")
    @Operation(
            summary = "List the published rate tables for a product",
            description = "Every version, in order, with its effective period and every factor it contains.")
    public List<RateTableResponse> rateTables(
            @Parameter(description = "e.g. PERSONAL_AUTO", required = true) @RequestParam String productCode) {
        return rating.rateTablesFor(productCode).stream()
                .map(RateTableResponse::from)
                .toList();
    }

    @GetMapping("/rate-tables/effective")
    @Operation(
            summary = "The rate table in force on a date",
            description = "Which published rates a risk effective on this date would be priced from.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The table in force"),
        @ApiResponse(responseCode = "422", description = "No table in force, or overlapping tables"),
    })
    public RateTableResponse effectiveRateTable(
            @RequestParam String productCode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDate) {
        return RateTableResponse.from(rating.selectRateTable(productCode, effectiveDate));
    }
}
