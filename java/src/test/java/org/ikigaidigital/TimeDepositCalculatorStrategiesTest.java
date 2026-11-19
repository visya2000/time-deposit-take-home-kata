package org.ikigaidigital;

import org.ikigaidigital.domain.interest.BasicInterestStrategy;
import org.ikigaidigital.domain.interest.InterestStrategies;
import org.ikigaidigital.domain.interest.InterestStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Calculator built from a collection of {@link InterestStrategy}: the extension point and plan lookup. */
class TimeDepositCalculatorStrategiesTest {

    /** A custom plan with its own rule: a flat 10.00 per month from day one (no grace period). */
    private static final class GoldInterestStrategy implements InterestStrategy {

        @Override
        public String planType() {
            return "gold";
        }

        @Override
        public double monthlyInterest(TimeDeposit deposit) {
            return 10.0;
        }
    }

    @Test
    void customPlan_isApplied_whileDefaultPlansKeepWorking() {
        TimeDepositCalculator calculator = new TimeDepositCalculator(
                Stream.concat(InterestStrategies.defaults().stream(), Stream.of(new GoldInterestStrategy())).toList());
        TimeDeposit gold = new TimeDeposit(1, "gold", 1200.00, 10);
        TimeDeposit basic = new TimeDeposit(2, "basic", 1200.00, 31);

        calculator.updateBalance(List.of(gold, basic));

        assertThat(gold.getBalance()).isEqualTo(1210.00);
        assertThat(basic.getBalance()).isEqualTo(1201.00);
    }

    @Test
    void calculatorWithOnlyCustomPlan_givesNoInterestToBasicPlan() {
        TimeDepositCalculator calculator = new TimeDepositCalculator(List.of(new GoldInterestStrategy()));
        TimeDeposit basic = new TimeDeposit(1, "basic", 1200.00, 31);

        calculator.updateBalance(List.of(basic));

        assertThat(basic.getBalance()).isEqualTo(1200.00);
    }

    @Test
    void duplicatePlanTypes_areRejectedOnConstruction() {
        List<InterestStrategy> duplicates = List.of(new BasicInterestStrategy(), new BasicInterestStrategy());

        assertThatThrownBy(() -> new TimeDepositCalculator(duplicates))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Duplicate plan type: basic");
    }

    @Test
    void nullPlanType_isTreatedAsUnknown_balanceUnchangedWithoutException() {
        TimeDeposit deposit = new TimeDeposit(1, null, 1200.00, 100);

        new TimeDepositCalculator().updateBalance(List.of(deposit));

        assertThat(deposit.getBalance()).isEqualTo(1200.00);
    }
}
