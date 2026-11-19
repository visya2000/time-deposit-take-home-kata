package org.ikigaidigital.domain.interest;

import org.ikigaidigital.TimeDeposit;

/**
 * Interest rule of one plan type. Each implementation owns its full eligibility rule (grace period
 * included), because the README applies the 30-day grace period to the "existing plans" only.
 */
public interface InterestStrategy {

    String planType();

    /** Unrounded monthly interest in {@code double}; rounding stays in the calculator. */
    double monthlyInterest(TimeDeposit deposit);
}
