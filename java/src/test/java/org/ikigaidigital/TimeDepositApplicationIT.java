package org.ikigaidigital;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "interest.business-zone=Asia/Tokyo")
@Import(TestcontainersConfiguration.class)
class TimeDepositApplicationIT {

    @Autowired
    private Clock clock;

    @Autowired
    private TimeDepositCalculator calculator;

    @Test
    void clock_usesTheConfiguredBusinessZone() {
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
    }

    // Boundaries are covered by the unit tests; here only: the bean knows every default plan.
    @Test
    void calculatorBean_appliesTheDefaultInterestPlans() {
        TimeDeposit basic = new TimeDeposit(1, "basic", 1200.00, 100);
        TimeDeposit student = new TimeDeposit(2, "student", 1200.00, 100);
        TimeDeposit premium = new TimeDeposit(3, "premium", 1200.00, 100);

        calculator.updateBalance(List.of(basic, student, premium));

        assertThat(List.of(basic.getBalance(), student.getBalance(), premium.getBalance()))
                .containsExactly(1201.00, 1203.00, 1205.00);
    }
}
