package org.ikigaidigital;

import org.ikigaidigital.domain.interest.InterestStrategies;
import org.ikigaidigital.domain.interest.InterestStrategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Adds one month of interest to each deposit's balance. The interest rule of each plan type is an
 * {@link InterestStrategy}; rounding stays here and is unchanged: the {@code double} interest is
 * rounded with {@code new BigDecimal(double)} to cents, HALF_UP (exact decimal maths would change
 * results at rounding edges, and the existing behaviour is declared correct).
 *
 * <p>Part of the shared kernel with {@link TimeDeposit} (see package-info): kept in this package
 * because moving it would break its callers. Suggested to move into
 * {@code org.ikigaidigital.domain.interest} (and {@code TimeDeposit} into
 * {@code org.ikigaidigital.domain}) in a coordinated breaking release (ADR 0006).
 */
public class TimeDepositCalculator {

    private final Map<String, InterestStrategy> strategiesByPlanType;

    public TimeDepositCalculator() {
        this(InterestStrategies.defaults());
    }

    /** @throws IllegalStateException if two strategies handle the same plan type */
    public TimeDepositCalculator(Collection<InterestStrategy> strategies) {
        // HashMap on purpose: get(null) returns null, so a null plan type is "unknown"
        // (immutable Map.of/copyOf maps would throw on get(null)).
        this.strategiesByPlanType = Collections.unmodifiableMap(strategies.stream()
                .collect(Collectors.toMap(InterestStrategy::planType, Function.identity(),
                        (first, second) -> {
                            throw new IllegalStateException("Duplicate plan type: " + first.planType());
                        },
                        HashMap::new)));
    }

    public void updateBalance(List<TimeDeposit> xs) {
        for (TimeDeposit deposit : xs) {
            double interest = monthlyInterest(deposit);
            deposit.setBalance(deposit.getBalance()
                    + new BigDecimal(interest).setScale(2, RoundingMode.HALF_UP).doubleValue());
        }
    }

    /**
     * Unknown plan types (matched case-sensitively, as before) earn no interest. Assumption: a null
     * plan type is invalid input and is treated as unknown; the original code threw a
     * NullPointerException for it only when days > 30.
     */
    private double monthlyInterest(TimeDeposit deposit) {
        InterestStrategy strategy = strategiesByPlanType.get(deposit.getPlanType());
        return strategy == null ? 0 : strategy.monthlyInterest(deposit);
    }
}
