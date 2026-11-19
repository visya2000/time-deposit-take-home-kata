package org.ikigaidigital.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Result of the balance update for one deposit: its new balance (in cents) and the scheduled due
 * date of the last posting applied, stored as its new marker.
 */
public record InterestPosting(int depositId, BigDecimal balance, LocalDate lastInterestAppliedOn) {
}
