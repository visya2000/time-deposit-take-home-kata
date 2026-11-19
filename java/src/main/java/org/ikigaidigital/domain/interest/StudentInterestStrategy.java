package org.ikigaidigital.domain.interest;

import org.ikigaidigital.TimeDeposit;

/** Student plan: 3 % p.a. after the 30-day grace period, none after one year (day 366 onwards). */
public class StudentInterestStrategy implements InterestStrategy {

    private static final double ANNUAL_RATE = 0.03;
    private static final int GRACE_PERIOD_DAYS = 30;
    private static final int LAST_INTEREST_DAY = 365;

    @Override
    public String planType() {
        return "student";
    }

    @Override
    public double monthlyInterest(TimeDeposit deposit) {
        int days = deposit.getDays();
        if (days <= GRACE_PERIOD_DAYS || days > LAST_INTEREST_DAY) {
            return 0;
        }
        return deposit.getBalance() * ANNUAL_RATE / 12;
    }
}
