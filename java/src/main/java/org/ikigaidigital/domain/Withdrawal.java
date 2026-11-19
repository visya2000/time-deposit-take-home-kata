package org.ikigaidigital.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Assumptions: the README does not define a withdrawal's shape; it is returned as id, amount, date.
 * Withdrawals do not affect interest (no such rule in the README); whether they change the
 * deposit's balance is not specified, so they are only read and returned.
 */
public record Withdrawal(int id, BigDecimal amount, LocalDate date) {
}
