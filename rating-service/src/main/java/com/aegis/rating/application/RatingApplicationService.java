package com.aegis.rating.application;

import com.aegis.rating.domain.Quote;
import com.aegis.rating.domain.RateTable;
import com.aegis.rating.domain.RatingEngine;
import com.aegis.rating.domain.RatingInput;
import com.aegis.rating.domain.RatingResult;
import com.aegis.rating.domain.UnratableRiskException;
import com.aegis.rating.repository.QuoteRepository;
import com.aegis.rating.repository.RateTableRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Selects the rate table, runs the engine and persists the result.
 *
 * <p>The split matters: everything that decides a number lives in {@link RatingEngine}, which is
 * pure. This class does the impure parts — reading the clock, reading the database, writing the
 * quote — and none of them can influence the premium.
 */
@Service
public class RatingApplicationService {

    private static final Logger log = LoggerFactory.getLogger(RatingApplicationService.class);

    private final RateTableRepository rateTables;
    private final QuoteRepository quotes;
    private final Clock clock;

    public RatingApplicationService(RateTableRepository rateTables, QuoteRepository quotes, Clock clock) {
        this.rateTables = rateTables;
        this.quotes = quotes;
        this.clock = clock;
    }

    @Transactional
    public Quote quote(RatingInput input) {
        RateTable table = selectRateTable(input.productCode(), input.effectiveDate());
        RatingResult result = RatingEngine.rate(table, input);

        Quote quote = Quote.from(nextQuoteNumber(), input, result, clock.instant());
        Quote saved = quotes.save(quote);

        log.info(
                "Quoted {} at {} using rate table {} effective {}",
                saved.quoteNumber(),
                saved.totalPremium(),
                table.describe(),
                input.effectiveDate());
        return saved;
    }

    @Transactional(readOnly = true)
    public Quote findByQuoteNumber(String quoteNumber) {
        return quotes.findByQuoteNumber(quoteNumber).orElseThrow(() -> new QuoteNotFoundException(quoteNumber));
    }

    @Transactional(readOnly = true)
    public List<RateTable> rateTablesFor(String productCode) {
        return rateTables.findByProductCodeOrderByTableVersionAsc(productCode.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * The table in force on the risk's effective date — never simply the newest one.
     *
     * <p>Re-rating a risk effective last March must select March's table and reproduce March's
     * premium, however many rate rises have been filed since.
     */
    @Transactional(readOnly = true)
    public RateTable selectRateTable(String productCode, LocalDate effectiveDate) {
        String product = productCode.toUpperCase(java.util.Locale.ROOT);
        List<RateTable> effective = rateTables.findEffectiveOn(product, effectiveDate);

        if (effective.isEmpty()) {
            throw new UnratableRiskException(
                    "No rate table is in force for product '" + product + "' on " + effectiveDate);
        }
        if (effective.size() > 1) {
            // Two published tables claiming the same date is a data error. Picking one would
            // price the risk from filed rates chosen at random.
            throw new UnratableRiskException("Product '" + product + "' has " + effective.size()
                    + " overlapping rate tables in force on " + effectiveDate + ": "
                    + effective.stream().map(RateTable::describe).toList());
        }
        return effective.getFirst();
    }

    private String nextQuoteNumber() {
        return "QT-%08d".formatted(quotes.nextQuoteSequence());
    }
}
