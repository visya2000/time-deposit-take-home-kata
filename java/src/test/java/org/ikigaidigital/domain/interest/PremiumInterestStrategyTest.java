package org.ikigaidigital.domain.interest;

import org.ikigaidigital.TimeDeposit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PremiumInterestStrategyTest {

    private final PremiumInterestStrategy strategy = new PremiumInterestStrategy();

    @Test
    void handlesPremiumPlanType() {
        assertThat(strategy.planType()).isEqualTo("premium");
    }

    // 1200.00 * 0.05 / 12 is exactly 5.0 in double.
    @ParameterizedTest(name = "{0} days -> {1}")
    @CsvSource({
            "45, 0.0",
            "46, 5.0",
    })
    void premiumPlan_earnsNoInterest_until45Days_thenFivePercentFrom46Days(int days, double expectedInterest) {
        TimeDeposit deposit = new TimeDeposit(1, "premium", 1200.00, days);

        assertThat(strategy.monthlyInterest(deposit)).isEqualTo(expectedInterest);
    }
}
