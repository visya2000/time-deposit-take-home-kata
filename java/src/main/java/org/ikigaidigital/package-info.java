/**
 * Root package. Besides the Spring Boot main class it holds the shared kernel: the two classes
 * {@link org.ikigaidigital.TimeDeposit} (externally owned, frozen) and
 * {@link org.ikigaidigital.TimeDepositCalculator} (its public signature and behaviour are a
 * published contract; the implementation behind it is ours). Other code depends on both by their
 * fully qualified names, so they stay here: moving them would be a breaking change (README: no
 * breaking changes). The domain may depend on these two classes; the interest strategies keep the
 * full {@code TimeDeposit} as input, so future rules can use more of the deposit than balance and
 * days.
 *
 * <p>Suggested future change, in a coordinated breaking release together with the other
 * consumers: move {@code TimeDeposit} into {@code org.ikigaidigital.domain} and
 * {@code TimeDepositCalculator} into {@code org.ikigaidigital.domain.interest}. That removes the
 * accepted package cycle between this package and {@code domain.interest} (ADR 0006).
 */
package org.ikigaidigital;
