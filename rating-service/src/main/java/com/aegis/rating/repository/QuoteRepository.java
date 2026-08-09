package com.aegis.rating.repository;

import com.aegis.rating.domain.Quote;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface QuoteRepository extends JpaRepository<Quote, UUID> {

    Optional<Quote> findByQuoteNumber(String quoteNumber);

    /**
     * Draws the next quote reference from a database sequence.
     *
     * <p>A sequence rather than {@code max(quote_number) + 1}: the latter needs a table lock to be
     * correct under concurrency, and silently reuses numbers after a deletion. Sequences are also
     * non-transactional, so a rolled-back quote burns a number rather than colliding with the
     * next one — the right trade when the number is a reference, not a count.
     */
    @Query(value = "select nextval('quote_number_seq')", nativeQuery = true)
    long nextQuoteSequence();
}
