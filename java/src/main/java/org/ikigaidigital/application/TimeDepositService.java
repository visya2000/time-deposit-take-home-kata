package org.ikigaidigital.application;

import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.TimeDepositCalculator;
import org.ikigaidigital.application.port.in.GetAllTimeDepositsUseCase;
import org.ikigaidigital.application.port.in.UpdateAllBalancesUseCase;
import org.ikigaidigital.application.port.out.TimeDepositRepository;
import org.ikigaidigital.application.port.out.TransactionRunner;
import org.ikigaidigital.domain.Cents;
import org.ikigaidigital.domain.DueTimeDeposit;
import org.ikigaidigital.domain.InterestPosting;
import org.ikigaidigital.domain.TimeDepositPage;
import org.ikigaidigital.domain.TimeDepositWithWithdrawals;
import org.ikigaidigital.domain.interest.InterestSchedule;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

public class TimeDepositService implements GetAllTimeDepositsUseCase, UpdateAllBalancesUseCase {

    private final TimeDepositRepository repository;
    private final TransactionRunner transactionRunner;
    private final TimeDepositCalculator calculator;
    private final Clock clock;
    private final int chunkSize;

    public TimeDepositService(TimeDepositRepository repository, TransactionRunner transactionRunner,
                              TimeDepositCalculator calculator, Clock clock, int chunkSize) {
        if (chunkSize < 1) {
            throw new IllegalArgumentException("chunk size must be at least 1, was " + chunkSize);
        }
        this.repository = repository;
        this.transactionRunner = transactionRunner;
        this.calculator = calculator;
        this.clock = clock;
        this.chunkSize = chunkSize;
    }

    @Override
    public List<TimeDepositWithWithdrawals> getAll() {
        return repository.findAllWithWithdrawals();
    }

    /** Fetches one extra deposit: it only signals that a next page exists and is not returned. */
    @Override
    public TimeDepositPage getPage(int afterId, int limit) {
        List<TimeDepositWithWithdrawals> deposits = repository.findPageWithWithdrawals(afterId, limit + 1);
        boolean hasNext = deposits.size() > limit;
        return new TimeDepositPage(hasNext ? deposits.subList(0, limit) : deposits, hasNext);
    }

    /**
     * Walks all due deposits in chunks, one transaction per chunk (a failure rolls back only the
     * current chunk; committed chunks are no longer due, so a re-run continues without double
     * posting). Assumption: called daily; interest is posted at most once per calendar month per
     * deposit — due again one calendar month (not 30 days) after it was last applied, because each
     * posting is a monthly rate (rate / 12). {@code today} is fixed once per run, in the clock's
     * business zone, so a run spanning midnight uses one date. Assumption: {@code days} (the
     * deposit's age) is maintained by another system and current when the run starts; this run
     * changes only the balance (and its scheduling marker), never {@code days}.
     */
    @Override
    public void updateAllBalances() {
        LocalDate today = LocalDate.now(clock);
        LocalDate cutoff = today.minusMonths(1);
        OptionalInt lastId = OptionalInt.of(0);
        while (lastId.isPresent()) {
            int afterId = lastId.getAsInt();
            lastId = transactionRunner.inTransaction(() -> applyInterestToNextChunk(cutoff, afterId, today));
        }
    }

    /**
     * Returns the highest id of the processed chunk, or empty when no due deposit is left. The
     * {@code afterId} cursor is essential: deposits that stay due (balance unchanged) would otherwise
     * be returned by every chunk query and the run would never end.
     */
    private OptionalInt applyInterestToNextChunk(LocalDate cutoff, int afterId, LocalDate today) {
        List<DueTimeDeposit> chunk = repository.lockNextDue(cutoff, afterId, chunkSize);
        if (chunk.isEmpty()) {
            return OptionalInt.empty();
        }
        List<InterestPosting> postings = chunk.stream()
                .flatMap(due -> backfill(due, today).stream())
                .toList();
        repository.saveAppliedInterest(postings);
        return chunk.stream().mapToInt(due -> due.deposit().getId()).max();
    }

    /**
     * Posts every month owed, exactly as daily runs would have done: one posting per due date up to
     * today, each judged by the deposit's age on that date and computed on the balance after the
     * previous posting, re-rounded to cents as a stored balance is. The marker becomes the last due
     * date that earned interest (daily runs only mark postings that changed the balance).
     * Assumption: missed months are owed; withdrawals in between are not known per date, so the
     * current balance is the starting point.
     */
    private Optional<InterestPosting> backfill(DueTimeDeposit due, LocalDate today) {
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

    /** The deposit's age on a past date; {@code days} is current as of today (assumption). */
    private static int ageOn(LocalDate date, TimeDeposit deposit, LocalDate today) {
        return deposit.getDays() - (int) ChronoUnit.DAYS.between(date, today);
    }
}
