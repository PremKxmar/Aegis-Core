package com.aegis.contracts.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @Nested
    @DisplayName("construction and scale normalisation")
    class Construction {

        @Test
        void normalisesToTheCurrencyMinorUnit() {
            assertThat(Money.of("10", "USD").amount()).isEqualTo(new BigDecimal("10.00"));
            assertThat(Money.of("10.5", "USD").amount()).isEqualTo(new BigDecimal("10.50"));
        }

        @Test
        void usesZeroDecimalPlacesForYen() {
            // JPY has no minor unit. A hard-coded scale of 2 would invent sen that do not exist.
            assertThat(Money.of("1500", "JPY").amount()).isEqualTo(new BigDecimal("1500"));
            assertThat(Money.of("1500.4", "JPY").amount()).isEqualTo(new BigDecimal("1500"));
        }

        @Test
        void usesThreeDecimalPlacesForDinar() {
            assertThat(Money.of("10.125", "KWD").amount()).isEqualTo(new BigDecimal("10.125"));
        }

        @Test
        void acceptsLowerCaseCurrencyCodes() {
            assertThat(Money.of("10.00", "usd")).isEqualTo(Money.of("10.00", "USD"));
            assertThat(Money.of("10.00", "usd").currencyCode()).isEqualTo("USD");
        }

        @ParameterizedTest
        @ValueSource(strings = {"US", "DOLLAR", "", "ZZZ", "12"})
        void rejectsCodesThatAreNotIso4217(String code) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> Money.of("1.00", code))
                    .withMessageContaining("Not a valid ISO-4217 currency code");
        }

        @ParameterizedTest
        @ValueSource(strings = {"XAU", "XDR"})
        void rejectsPseudoCurrenciesThatHaveNoMinorUnit(String code) {
            // Java reports -1 minor units for these; setScale(-1) would round to the nearest ten.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> Money.of("1.00", code))
                    .withMessageContaining("pseudo-currency");
        }

        @Test
        void rejectsNullInputs() {
            assertThatNullPointerException().isThrownBy(() -> Money.of((BigDecimal) null, "USD"));
            assertThatNullPointerException().isThrownBy(() -> Money.of((String) null, "USD"));
            assertThatNullPointerException().isThrownBy(() -> Money.of("1.00", null));
        }

        @Test
        void zeroIsZeroInTheRequestedCurrency() {
            Money zero = Money.zero("EUR");
            assertThat(zero.isZero()).isTrue();
            assertThat(zero.currencyCode()).isEqualTo("EUR");
            assertThat(zero.amount()).isEqualTo(new BigDecimal("0.00"));
        }
    }

    @Nested
    @DisplayName("HALF_EVEN rounding")
    class Rounding {

        @Test
        void isDeclaredOnceAndIsBankersRounding() {
            assertThat(Money.ROUNDING_MODE).isEqualTo(RoundingMode.HALF_EVEN);
        }

        @ParameterizedTest(name = "{0} USD -> {1} USD")
        @CsvSource({
            // Ties round to the nearest EVEN last digit, which is what makes the mode unbiased.
            "2.345, 2.34",
            "2.355, 2.36",
            "2.365, 2.36",
            "2.375, 2.38",
            "-2.345, -2.34",
            "-2.355, -2.36",
            // Non-ties are unaffected.
            "2.341, 2.34",
            "2.346, 2.35",
        })
        void roundsTiesToEven(String input, String expected) {
            assertThat(Money.of(input, "USD").amount()).isEqualTo(new BigDecimal(expected));
        }

        @Test
        void halfUpWouldHaveBiasedTheSameSample() {
            // Guards the *choice*: over these four ties HALF_EVEN nets to the same total as the
            // unrounded input, whereas HALF_UP drifts two cents high. That drift is the reason
            // the platform does not use HALF_UP.
            String[] ties = {"2.345", "2.355", "2.365", "2.375"};
            BigDecimal exact = BigDecimal.ZERO;
            BigDecimal halfEven = BigDecimal.ZERO;
            BigDecimal halfUp = BigDecimal.ZERO;
            for (String tie : ties) {
                BigDecimal value = new BigDecimal(tie);
                exact = exact.add(value);
                halfEven = halfEven.add(Money.of(tie, "USD").amount());
                halfUp = halfUp.add(value.setScale(2, RoundingMode.HALF_UP));
            }
            assertThat(halfEven).isEqualByComparingTo(exact);
            assertThat(halfUp).isEqualByComparingTo(exact.add(new BigDecimal("0.02")));
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        void addsAndSubtracts() {
            Money ten = Money.of("10.00", "USD");
            Money three = Money.of("3.50", "USD");
            assertThat(ten.plus(three)).isEqualTo(Money.of("13.50", "USD"));
            assertThat(ten.minus(three)).isEqualTo(Money.of("6.50", "USD"));
        }

        @Test
        void subtractionCanGoNegative() {
            // Ledger reversals depend on this: a correcting entry is a negative amount, not a
            // deleted row.
            Money result = Money.of("10.00", "USD").minus(Money.of("25.00", "USD"));
            assertThat(result).isEqualTo(Money.of("-15.00", "USD"));
            assertThat(result.isNegative()).isTrue();
            assertThat(result.isPositive()).isFalse();
        }

        @Test
        void multipliesByARatingFactor() {
            assertThat(Money.of("100.00", "USD").times(new BigDecimal("1.15"))).isEqualTo(Money.of("115.00", "USD"));
        }

        @Test
        void multipliesByAWholeNumber() {
            assertThat(Money.of("12.50", "USD").times(4)).isEqualTo(Money.of("50.00", "USD"));
        }

        @Test
        void roundsTheProductBackToTheCurrencyScale() {
            assertThat(Money.of("100.00", "USD")
                            .times(new BigDecimal("1.11115"))
                            .amount())
                    .isEqualTo(new BigDecimal("111.12"));
        }

        @Test
        void negatesInPlaceOfDeletingALedgerRow() {
            assertThat(Money.of("42.00", "USD").negated()).isEqualTo(Money.of("-42.00", "USD"));
            assertThat(Money.zero("USD").negated()).isEqualTo(Money.zero("USD"));
        }

        @Test
        void maxAppliesAMinimumPremiumFloor() {
            Money floor = Money.of("250.00", "USD");
            assertThat(Money.of("100.00", "USD").max(floor)).isEqualTo(floor);
            assertThat(Money.of("900.00", "USD").max(floor)).isEqualTo(Money.of("900.00", "USD"));
        }

        @Test
        void minCapsAPaymentAtTheRemainingLimit() {
            Money limit = Money.of("5000.00", "USD");
            assertThat(Money.of("7500.00", "USD").min(limit)).isEqualTo(limit);
            assertThat(Money.of("120.00", "USD").min(limit)).isEqualTo(Money.of("120.00", "USD"));
        }

        @Test
        void refusesToMixCurrencies() {
            Money usd = Money.of("10.00", "USD");
            Money eur = Money.of("10.00", "EUR");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> usd.plus(eur))
                    .withMessageContaining("different currencies");
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> usd.minus(eur));
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> usd.max(eur));
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> usd.min(eur));
            assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> usd.compareTo(eur));
        }

        @Test
        void rejectsNullOperands() {
            Money usd = Money.of("10.00", "USD");
            assertThatNullPointerException().isThrownBy(() -> usd.plus(null));
            assertThatNullPointerException().isThrownBy(() -> usd.times((BigDecimal) null));
        }

        @Test
        void additionIsAssociativeAcrossManySmallAmounts() {
            // The canonical floating-point failure: ten dimes must be exactly one dollar.
            Money total = Money.zero("USD");
            for (int i = 0; i < 10; i++) {
                total = total.plus(Money.of("0.10", "USD"));
            }
            assertThat(total).isEqualTo(Money.of("1.00", "USD"));
            assertThat(total.amount()).isEqualTo(new BigDecimal("1.00"));
        }
    }

    @Nested
    @DisplayName("equality and ordering")
    class EqualityAndOrdering {

        @Test
        void equalsIgnoresTheScaleTheValueArrivedWith() {
            // This is the case Hibernate produces: a NUMERIC(19,4) column yields 10.0000.
            assertThat(Money.of(new BigDecimal("10.0000"), "USD")).isEqualTo(Money.of("10.00", "USD"));
            assertThat(Money.of(new BigDecimal("10.0000"), "USD").hashCode())
                    .isEqualTo(Money.of("10.00", "USD").hashCode());
        }

        @Test
        void differentCurrenciesAreNeverEqual() {
            assertThat(Money.of("10.00", "USD")).isNotEqualTo(Money.of("10.00", "EUR"));
        }

        @Test
        void equalsHandlesNullAndForeignTypes() {
            Money usd = Money.of("10.00", "USD");
            assertThat(usd).isNotEqualTo(null).isNotEqualTo("10.00 USD").isEqualTo(usd);
        }

        @Test
        void ordersAmountsInTheSameCurrency() {
            assertThat(Money.of("10.00", "USD")).isGreaterThan(Money.of("9.99", "USD"));
            assertThat(Money.of("10.00", "USD")).isLessThan(Money.of("10.01", "USD"));
            assertThat(Money.of("10.00", "USD")).isEqualByComparingTo(Money.of("10.00", "USD"));
        }

        @Test
        void comparisonAgreesWithEquality() {
            Money a = Money.of("10.00", "USD");
            Money b = Money.of(new BigDecimal("10.0000"), "USD");
            assertThat(a.compareTo(b)).isZero();
            assertThat(a).isEqualTo(b);
        }
    }

    @Nested
    @DisplayName("JSON wire format")
    class Json {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        void serialisesTheAmountAsAStringToProtectPrecision() throws Exception {
            // Serialising as a JSON number invites consumers to parse it into an IEEE-754
            // double, which is exactly the loss of precision this class exists to prevent.
            assertThat(mapper.writeValueAsString(Money.of("1250.00", "USD")))
                    .isEqualTo("{\"amount\":\"1250.00\",\"currency\":\"USD\"}");
        }

        @Test
        void roundTripsThroughJson() throws Exception {
            Money original = Money.of("98765.43", "EUR");
            Money restored = mapper.readValue(mapper.writeValueAsString(original), Money.class);
            assertThat(restored).isEqualTo(original);
        }

        @Test
        void deserialisesAnAmountSuppliedAsAJsonNumber() throws Exception {
            // Clients will send numbers whether we like it or not; accept them, emit strings.
            Money restored = mapper.readValue("{\"amount\":1250.5,\"currency\":\"USD\"}", Money.class);
            assertThat(restored).isEqualTo(Money.of("1250.50", "USD"));
        }
    }

    @Test
    void toStringPutsTheAmountFirstForReadableLogLines() {
        assertThat(Money.of("1250.00", "USD")).hasToString("1250.00 USD");
        assertThat(Money.of("-3.05", "USD")).hasToString("-3.05 USD");
    }

    @Test
    void currencyExposesTheMinorUnit() {
        assertThat(Money.of("1.00", "USD").currency().getDefaultFractionDigits())
                .isEqualTo(2);
        assertThat(Money.of("1", "JPY").currency().getDefaultFractionDigits()).isZero();
    }
}
