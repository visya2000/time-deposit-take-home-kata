package org.ikigaidigital.domain.interest;

import org.ikigaidigital.TimeDeposit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class BasicInterestStrategyTest {

    private final BasicInterestStrategy strategy = new BasicInterestStrategy();

    @Test
    void handlesBasicPlanType() {
        assertThat(strategy.planType()).isEqualTo("basic");
    }

    // 1200.00 * 0.01 / 12 is exactly 1.0 in double.
    @ParameterizedTest(name = "{0} days -> {1}")
    @CsvSource({
            "30, 0.0",
            "31, 1.0",
    })
    void basicPlan_earnsNoInterest_until30Days_thenOnePercentFrom31Days(int days, double expectedInterest) {
        TimeDeposit deposit = new TimeDeposit(1, "basic", 1200.00, days);

        assertThat(strategy.monthlyInterest(deposit)).isEqualTo(expectedInterest);
    }
}
