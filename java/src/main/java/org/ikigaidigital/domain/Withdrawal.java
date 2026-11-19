package org.ikigaidigital.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Assumption: the README does not define a withdrawal's shape; it is returned as id, amount, date. */
public record Withdrawal(int id, BigDecimal amount, LocalDate date) {
}
