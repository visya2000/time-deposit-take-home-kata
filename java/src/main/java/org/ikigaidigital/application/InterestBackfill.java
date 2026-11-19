package org.ikigaidigital.application;

import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.TimeDepositCalculator;
import org.ikigaidigital.domain.Cents;
import org.ikigaidigital.domain.DueTimeDeposit;
import org.ikigaidigital.domain.InterestPosting;
import org.ikigaidigital.domain.interest.InterestSchedule;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * The interest a due deposit is owed on a run (PLAN.md §3 "Backfilling", ADR 0007): every month
 * owed, exactly as daily runs would have posted it — one posting per due date up to today, each
 * judged by the deposit's age on that date and computed by the frozen calculator on the balance
 * after the previous posting, re-rounded to cents as a stored balance is. The marker becomes the
 * last due date that earned interest (daily runs only mark postings that changed the balance).
 * Assumptions: missed months are owed; withdrawals in between are not known per date, so the
 * current balance is the starting point; {@code days} is current as of today.
 */
final class InterestBackfill {

    private final TimeDepositCalculator calculator;

    InterestBackfill(TimeDepositCalculator calculator) {
        this.calculator = calculator;
    }

    /** The posting to save for this deposit, or empty when no due date earned interest. */
    Optional<InterestPosting> owedInterest(DueTimeDeposit due, LocalDate today) {
        TimeDeposit deposit = due.deposit();
        double balance = deposit.getBalance();
        LocalDate lastPosting = null;
        for (LocalDate dueDate : dueDates(due, today)) {
            double newBalance = balanceAfterOneMonth(deposit, balance, ageOn(dueDate, deposit, today));
            if (newBalance != balance) {
                balance = newBalance;
                lastPosting = dueDate;
            }
        }
        return lastPosting == null
                ? Optional.empty()
                : Optional.of(new InterestPosting(deposit.getId(), Cents.of(balance), lastPosting));
    }

    /**
     * Due dates up to today: the months after the last posting; for a deposit never posted, from the
     * first day it was eligible (as daily runs would have posted it), found by asking the calculator
     * rather than duplicating any plan's rule.
     */
    private List<LocalDate> dueDates(DueTimeDeposit due, LocalDate today) {
        if (due.lastInterestAppliedOn() != null) {
            return InterestSchedule.dueDatesFrom(InterestSchedule.next(due.lastInterestAppliedOn()), today);
        }
        TimeDeposit deposit = due.deposit();
        for (int age = 0; age <= deposit.getDays(); age++) {
            if (balanceAfterOneMonth(deposit, deposit.getBalance(), age) != deposit.getBalance()) {
                return InterestSchedule.dueDatesFrom(today.minusDays(deposit.getDays() - age), today);
            }
        }
        return List.of();
    }

    /** One posting by the frozen calculator on a snapshot of the deposit at the given age. */
    private double balanceAfterOneMonth(TimeDeposit deposit, double balance, int age) {
        TimeDeposit snapshot = new TimeDeposit(deposit.getId(), deposit.getPlanType(), balance, age);
        calculator.updateBalance(List.of(snapshot));
        return Cents.round(snapshot.getBalance());
    }

    /** The deposit's age on a past date. */
    private static int ageOn(LocalDate date, TimeDeposit deposit, LocalDate today) {
        return deposit.getDays() - (int) ChronoUnit.DAYS.between(date, today);
    }
}
