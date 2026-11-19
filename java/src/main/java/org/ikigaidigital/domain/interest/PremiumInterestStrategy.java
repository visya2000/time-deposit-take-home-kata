package org.ikigaidigital.domain.interest;

import org.ikigaidigital.TimeDeposit;

/** Premium plan: 5 % p.a. starting after 45 days (which also covers the 30-day grace period). */
public class PremiumInterestStrategy implements InterestStrategy {

    private static final double ANNUAL_RATE = 0.05;
    private static final int NO_INTEREST_DAYS = 45;

    @Override
    public String planType() {
        return "premium";
    }

    @Override
    public double monthlyInterest(TimeDeposit deposit) {
        if (deposit.getDays() <= NO_INTEREST_DAYS) {
            return 0;
        }
        return deposit.getBalance() * ANNUAL_RATE / 12;
    }
}
