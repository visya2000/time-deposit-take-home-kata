package org.ikigaidigital.domain.interest;

import org.ikigaidigital.TimeDeposit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class StudentInterestStrategyTest {

    private final StudentInterestStrategy strategy = new StudentInterestStrategy();

    @Test
    void handlesStudentPlanType() {
        assertThat(strategy.planType()).isEqualTo("student");
    }

    // 1200.00 * 0.03 / 12 is exactly 3.0 in double.
    @ParameterizedTest(name = "{0} days -> {1}")
    @CsvSource({
            "30,  0.0",
            "31,  3.0",
            "365, 3.0",
            "366, 0.0",
    })
    void studentPlan_earnsThreePercent_from31To365Days_only(int days, double expectedInterest) {
        TimeDeposit deposit = new TimeDeposit(1, "student", 1200.00, days);

        assertThat(strategy.monthlyInterest(deposit)).isEqualTo(expectedInterest);
    }
}
