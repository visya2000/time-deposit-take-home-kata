package org.ikigaidigital.domain.interest;

import org.ikigaidigital.TimeDeposit;

/**
 * Interest rule of one plan type. Each implementation owns its full eligibility rule (grace period
 * included), because the README applies the 30-day grace period to the "existing plans" only.
 *
 * <p>The back-fill of missed months (ADR 0007) assumes the deposit's age range in which a plan
 * pays interest is one contiguous window (as for basic, student and premium); a plan that pays
 * again after a pause would be back-filled only on its monthly due dates.
 */
public interface InterestStrategy {

    String planType();

    /** Unrounded monthly interest in {@code double}; rounding stays in the calculator. */
    double monthlyInterest(TimeDeposit deposit);
}
