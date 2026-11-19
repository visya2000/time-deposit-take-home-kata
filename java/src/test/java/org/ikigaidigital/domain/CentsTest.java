package org.ikigaidigital.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** The one cents-rounding rule for stored balances (PLAN.md §2 "Money types", §3 "Backfilling"). */
class CentsTest {

    // 1000.10 + 0.83 is 1000.9300000000001 in double: the float error must not reach the balance.
    // 1.005 pins BigDecimal.valueOf (the decimal the double prints as): new BigDecimal(1.005) is
    // 1.00499999... and would round to 1.00. 1.005 and 2.665 also pin HALF_UP (HALF_EVEN: 1.00, 2.66).
    // isEqualTo compares BigDecimal with equals, so the expected scale 2 is asserted as well
    // (1201.0 -> 1201.00, 0.0 -> 0.00).
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "1000.9300000000001, 1000.93",
            "1.005,              1.01",
            "2.665,              2.67",
            "1.004,              1.00",
            "1201.0,             1201.00",
            "0.0,                0.00"
    })
    void roundsHalfUpToCents_fromTheDecimalTheDoublePrintsAs(double amount, String expected) {
        assertThat(Cents.of(amount)).isEqualTo(new BigDecimal(expected));
    }

    // round is the same rule for the next calculation input (a double for the frozen calculator).
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "1000.9300000000001, 1000.93",
            "1.005,              1.01",
            "3.5999999999999996, 3.60"
    })
    void round_appliesTheSameRule_andReturnsADouble(double amount, double expected) {
        assertThat(Cents.round(amount)).isEqualTo(expected);
    }
}
