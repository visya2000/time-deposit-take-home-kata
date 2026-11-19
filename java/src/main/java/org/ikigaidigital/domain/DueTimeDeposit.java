package org.ikigaidigital.domain;

import org.ikigaidigital.TimeDeposit;

import java.time.LocalDate;

/**
 * A deposit locked for the balance update, with the scheduled due date of its last interest
 * posting ({@code null} = never posted). The frozen {@link TimeDeposit} has no such field.
 */
public record DueTimeDeposit(TimeDeposit deposit, LocalDate lastInterestAppliedOn) {
}
