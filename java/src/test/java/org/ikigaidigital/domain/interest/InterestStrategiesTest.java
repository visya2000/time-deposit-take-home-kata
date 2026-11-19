package org.ikigaidigital.domain.interest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InterestStrategiesTest {

    @Test
    void defaults_registerExactlyTheBasicStudentAndPremiumPlans() {
        assertThat(InterestStrategies.defaults())
                .extracting(InterestStrategy::planType)
                .containsExactlyInAnyOrder("basic", "student", "premium");
    }
}
