package org.ikigaidigital.domain.interest;

import org.ikigaidigital.TimeDeposit;

/** Basic plan: 1 % p.a. after the 30-day grace period. */
public class BasicInterestStrategy implements InterestStrategy {

    private static final double ANNUAL_RATE = 0.01;
    private static final int GRACE_PERIOD_DAYS = 30;

    @Override
    public String planType() {
        return "basic";
    }

    @Override
    public double monthlyInterest(TimeDeposit deposit) {
        if (deposit.getDays() <= GRACE_PERIOD_DAYS) {
            return 0;
        }
        return deposit.getBalance() * ANNUAL_RATE / 12;
    }
}
