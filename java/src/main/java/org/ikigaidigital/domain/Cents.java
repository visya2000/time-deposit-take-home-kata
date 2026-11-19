package org.ikigaidigital.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The single cents-rounding rule for balances: {@code BigDecimal.valueOf(d).setScale(2, HALF_UP)}
 * (the decimal the double prints as, re-quantised to cents). Balances are stored in cents, so the
 * back-fill rounds between postings exactly as on-time runs do when they store a balance.
 */
public final class Cents {

    private Cents() {
    }

    public static BigDecimal of(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
    }

    /** The same rule for the next calculation input, which the frozen calculator takes as double. */
    public static double round(double amount) {
        return of(amount).doubleValue();
    }
}
