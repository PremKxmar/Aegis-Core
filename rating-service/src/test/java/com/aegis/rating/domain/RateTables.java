package com.aegis.rating.domain;

import com.aegis.contracts.money.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Builds rate tables in memory for tests.
 *
 * <p>Lives in the domain package so it can reach the package-private constructors. In production
 * rate tables come from Flyway migrations; nothing in the application builds one.
 */
public final class RateTables {

    public static final String USD = "USD";

    private RateTables() {}

    static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /**
     * A table with the same shape as the seeded PERSONAL_AUTO v1, with round factors so the
     * arithmetic in the worked-example tests can be checked by hand.
     */
    public static RateTable standardAutoTable() {
        return new RateTable(
                        "PERSONAL_AUTO",
                        1,
                        LocalDate.parse("2026-01-01"),
                        null,
                        Instant.parse("2025-11-01T00:00:00Z"),
                        bd("0.90"),
                        Money.of("250.00", USD))
                .withBaseRate("COLLISION", bd("4.00"))
                .withBaseRate("COMPREHENSIVE", bd("2.00"))
                .withTerritory("TX-DALLAS", bd("1.20"))
                .withTerritory("IA-RURAL", bd("0.80"))
                .withAgeBand(0, 5, bd("1.00"))
                .withAgeBand(5, null, bd("1.50"))
                .withClaimsBand(0, 1, bd("1.00"))
                .withClaimsBand(1, null, bd("2.00"));
    }

    /** A table whose bands overlap — invalid data, used to prove the engine refuses to guess. */
    public static RateTable tableWithOverlappingAgeBands() {
        return new RateTable(
                        "BROKEN",
                        1,
                        LocalDate.parse("2026-01-01"),
                        null,
                        Instant.parse("2025-11-01T00:00:00Z"),
                        bd("0.90"),
                        Money.of("250.00", USD))
                .withBaseRate("COLLISION", bd("4.00"))
                .withTerritory("TX-DALLAS", bd("1.00"))
                .withAgeBand(0, 10, bd("1.00"))
                .withAgeBand(5, 15, bd("1.30"))
                .withClaimsBand(0, null, bd("1.00"));
    }

    /** A table with a gap in its age bands — nothing covers ages 5 to 9. */
    public static RateTable tableWithGapInAgeBands() {
        return new RateTable(
                        "BROKEN",
                        1,
                        LocalDate.parse("2026-01-01"),
                        null,
                        Instant.parse("2025-11-01T00:00:00Z"),
                        bd("0.90"),
                        Money.of("250.00", USD))
                .withBaseRate("COLLISION", bd("4.00"))
                .withTerritory("TX-DALLAS", bd("1.00"))
                .withAgeBand(0, 5, bd("1.00"))
                .withAgeBand(10, null, bd("1.30"))
                .withClaimsBand(0, null, bd("1.00"));
    }

    /** A table priced in euros, for the currency-mismatch case. */
    public static RateTable euroTable() {
        return new RateTable(
                        "PERSONAL_AUTO",
                        1,
                        LocalDate.parse("2026-01-01"),
                        null,
                        Instant.parse("2025-11-01T00:00:00Z"),
                        bd("0.90"),
                        Money.of("250.00", "EUR"))
                .withBaseRate("COLLISION", bd("4.00"))
                .withTerritory("TX-DALLAS", bd("1.00"))
                .withAgeBand(0, null, bd("1.00"))
                .withClaimsBand(0, null, bd("1.00"));
    }

    /**
     * A table generated from arbitrary but valid parameters, used by the property tests. Bands
     * are built to tile the whole domain with no gaps and no overlaps, because a table that does
     * not is invalid data and the engine's guarantees are only claimed for valid tables.
     */
    public static RateTable generatedTable(
            BigDecimal collisionRate,
            BigDecimal territoryFactor,
            BigDecimal ageFactor,
            BigDecimal claimsFactor,
            BigDecimal discountFactor,
            Money minimumPremium) {
        return new RateTable(
                        "GENERATED",
                        1,
                        LocalDate.parse("2026-01-01"),
                        null,
                        Instant.parse("2025-11-01T00:00:00Z"),
                        discountFactor,
                        minimumPremium)
                .withBaseRate("COLLISION", collisionRate)
                .withTerritory("TX-DALLAS", territoryFactor)
                .withAgeBand(0, null, ageFactor)
                .withClaimsBand(0, null, claimsFactor);
    }
}
