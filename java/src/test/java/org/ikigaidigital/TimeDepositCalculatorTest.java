package org.ikigaidigital;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Characterization tests: they pin down what {@link TimeDepositCalculator#updateBalance} does today
 * (double arithmetic, HALF_UP rounding of the monthly interest to 2 decimals), so any refactoring
 * must keep these exact results.
 */
class TimeDepositCalculatorTest {

    // 1200.00 gives whole-number monthly interest: basic +1.00, student +3.00, premium +5.00.
    private static final double BALANCE = 1200.00;

    private final TimeDepositCalculator calculator = new TimeDepositCalculator();

    private double balanceAfterUpdate(String planType, double balance, int days) {
        TimeDeposit deposit = new TimeDeposit(1, planType, balance, days);
        calculator.updateBalance(List.of(deposit));
        return deposit.getBalance();
    }

    @ParameterizedTest(name = "{0}, {1} days: 1200.00 -> {2}")
    @CsvSource({
            "basic,   30, 1200.00",
            "basic,   31, 1201.00",
            "student, 30, 1200.00",
            "student, 31, 1203.00",
            "premium, 30, 1200.00",
    })
    void noPlanEarnsInterest_within30DayGracePeriod(String planType, int days, double expectedBalance) {
        assertThat(balanceAfterUpdate(planType, BALANCE, days)).isEqualTo(expectedBalance);
    }

    @ParameterizedTest(name = "premium, {0} days: 1200.00 -> {1}")
    @CsvSource({
            "31, 1200.00",
            "40, 1200.00",
            "45, 1200.00",
            "46, 1205.00",
    })
    void premiumPlan_earnsNoInterest_until45Days_thenFivePercentFrom46Days(int days, double expectedBalance) {
        assertThat(balanceAfterUpdate("premium", BALANCE, days)).isEqualTo(expectedBalance);
    }

    @ParameterizedTest(name = "student, {0} days: 1200.00 -> {1}")
    @CsvSource({
            "365, 1203.00",
            "366, 1200.00",
    })
    void studentPlan_earnsThreePercentUpTo365Days_andNothingFrom366Days(int days, double expectedBalance) {
        assertThat(balanceAfterUpdate("student", BALANCE, days)).isEqualTo(expectedBalance);
    }

    @ParameterizedTest(name = "{0}, 400 days: 1200.00 -> {1}")
    @CsvSource({
            "basic,   1201.00",
            "premium, 1205.00",
    })
    void basicAndPremiumPlans_haveNoUpperDayLimit(String planType, double expectedBalance) {
        assertThat(balanceAfterUpdate(planType, BALANCE, 400)).isEqualTo(expectedBalance);
    }

    @ParameterizedTest(name = "plan type \"{0}\" is unknown -> balance unchanged")
    @CsvSource({
            "gold",
            "Basic",
    })
    void unknownOrDifferentlyCasedPlanType_earnsNoInterest(String planType) {
        assertThat(balanceAfterUpdate(planType, BALANCE, 100)).isEqualTo(BALANCE);
    }

    @ParameterizedTest(name = "{0}, {1} days: zero balance stays zero")
    @CsvSource({
            "basic,   31",
            "student, 31",
            "premium, 46",
    })
    void zeroBalance_staysZero_evenWhenEligibleForInterest(String planType, int days) {
        assertThat(balanceAfterUpdate(planType, 0.00, days)).isEqualTo(0.00);
    }

    /*
     * Rounding edges per plan: the interest is computed in double exactly as `balance * rate / 12`
     * and rounded with new BigDecimal(double).setScale(2, HALF_UP). Each row lands on the other side
     * of the HALF_UP boundary from exact decimal maths, from `balance * (rate / 12)` and (except
     * basic 18.00) from `balance / 12 * rate` — so a rewrite of the expression is caught:
     *   basic 18.00 -> 18.01 (exact: 18.02), basic 30.00 -> 30.02 (others: 30.03),
     *   student 10.00 -> 10.02 (others: 10.03), premium 162.00 -> 162.67 (others: 162.68).
     */
    @ParameterizedTest(name = "{0}, {1} days: {2} -> {3}")
    @CsvSource({
            "basic,   31,  18.00,  18.01",
            "basic,   31,  30.00,  30.02",
            "student, 31,  10.00,  10.02",
            "premium, 46, 162.00, 162.67",
    })
    void monthlyInterest_isBalanceTimesRateDividedBy12InDouble_roundedHalfUpToCents(
            String planType, int days, double balance, double expectedBalance) {
        assertThat(balanceAfterUpdate(planType, balance, days)).isEqualTo(expectedBalance);
    }

    @Test
    void basicPlan_largeBalance_earnsMonthlyInterestRoundedToCents() {
        // The scenario of the original no-op test: 1,234,567.00 * 0.01 / 12 = 1,028.805833... -> 1,028.81
        assertThat(balanceAfterUpdate("basic", 1_234_567.00, 45)).isEqualTo(1_235_595.81);
    }

    @Test
    void emptyList_isAccepted() {
        assertThatCode(() -> calculator.updateBalance(List.of())).doesNotThrowAnyException();
    }

    @Test
    void mixedList_eachDepositIsUpdatedIndependentlyByItsOwnPlan() {
        TimeDeposit basic = new TimeDeposit(1, "basic", 1200.00, 31);
        TimeDeposit expiredStudent = new TimeDeposit(2, "student", 2400.00, 400);
        TimeDeposit premium = new TimeDeposit(3, "premium", 2400.00, 46);
        TimeDeposit unknown = new TimeDeposit(4, "gold", 1200.00, 100);
        TimeDeposit student = new TimeDeposit(5, "student", 2400.00, 31);

        calculator.updateBalance(List.of(basic, expiredStudent, premium, unknown, student));

        assertThat(basic.getBalance()).isEqualTo(1201.00);
        assertThat(expiredStudent.getBalance()).isEqualTo(2400.00);
        assertThat(premium.getBalance()).isEqualTo(2410.00);
        assertThat(unknown.getBalance()).isEqualTo(1200.00);
        assertThat(student.getBalance()).isEqualTo(2406.00);
    }

    @Test
    void updateBalance_leavesIdPlanTypeAndDaysUntouched() {
        TimeDeposit deposit = new TimeDeposit(42, "premium", 1200.00, 46);

        calculator.updateBalance(List.of(deposit));

        assertThat(deposit.getId()).isEqualTo(42);
        assertThat(deposit.getPlanType()).isEqualTo("premium");
        assertThat(deposit.getDays()).isEqualTo(46);
        assertThat(deposit.getBalance()).isEqualTo(1205.00);
    }
}
