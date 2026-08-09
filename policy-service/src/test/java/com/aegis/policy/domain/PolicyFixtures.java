package com.aegis.policy.domain;

import com.aegis.contracts.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Builders that keep the temporal tests readable, so the cases stand out and the setup does not. */
final class PolicyFixtures {

    static final String USD = "USD";
    static final String COLLISION = "COLLISION";
    static final String COMPREHENSIVE = "COMPREHENSIVE";

    /** Transaction-time instants, named for the day they represent. */
    static final Instant RECORDED_AT_BINDING = instant("2026-01-01");

    private PolicyFixtures() {}

    static LocalDate date(String iso) {
        return LocalDate.parse(iso);
    }

    static Instant instant(String isoDate) {
        return LocalDate.parse(isoDate).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    }

    static Coverage collision(String limit) {
        return new Coverage(COLLISION, Money.of(limit, USD), Money.of("500.00", USD), Set.of());
    }

    static Coverage collision(String limit, Set<String> exclusions) {
        return new Coverage(COLLISION, Money.of(limit, USD), Money.of("500.00", USD), exclusions);
    }

    static Coverage comprehensive(String limit) {
        return new Coverage(COMPREHENSIVE, Money.of(limit, USD), Money.of("250.00", USD), Set.of());
    }

    /** A one-year auto policy running 2026-01-01 to 2027-01-01 (exclusive), bound on day one. */
    static Policy annualAutoPolicy(String collisionLimit) {
        return Policy.bind(
                "AUTO-0001",
                "PERSONAL_AUTO",
                date("2026-01-01"),
                date("2027-01-01"),
                RECORDED_AT_BINDING,
                "Priya Raman",
                "TX-DALLAS",
                List.of(collision(collisionLimit)));
    }

    /** The collision limit on the version resolved for a date, as a plain string for assertions. */
    static String collisionLimitOn(Policy policy, String validDate, String asAtDate) {
        return policy.versionAsOf(date(validDate), instant(asAtDate))
                .flatMap(v -> v.coverage(COLLISION))
                .map(c -> c.limit().amount().toPlainString())
                .orElse(null);
    }
}
