package org.ikigaidigital.domain.interest;

import java.util.List;

/** The single registration point of the interest plans: a new plan is added here. */
public final class InterestStrategies {

    private InterestStrategies() {
    }

    public static List<InterestStrategy> defaults() {
        return List.of(
                new BasicInterestStrategy(),
                new StudentInterestStrategy(),
                new PremiumInterestStrategy());
    }
}
