package org.ikigaidigital.application;

import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.TimeDepositCalculator;
import org.ikigaidigital.application.port.in.GetAllTimeDepositsUseCase;
import org.ikigaidigital.application.port.in.UpdateAllBalancesUseCase;
import org.ikigaidigital.application.port.out.TimeDepositRepository;
import org.ikigaidigital.application.port.out.TransactionRunner;
import org.ikigaidigital.domain.TimeDepositPage;
import org.ikigaidigital.domain.TimeDepositWithWithdrawals;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.IntStream;

public class TimeDepositService implements GetAllTimeDepositsUseCase, UpdateAllBalancesUseCase {

    private final TimeDepositRepository repository;
    private final TransactionRunner transactionRunner;
    private final TimeDepositCalculator calculator;
    private final Clock clock;
    private final int chunkSize;

    public TimeDepositService(TimeDepositRepository repository, TransactionRunner transactionRunner,
                              TimeDepositCalculator calculator, Clock clock, int chunkSize) {
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
     * business zone, so a run spanning midnight uses one date.
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
        List<TimeDeposit> chunk = repository.lockNextDue(cutoff, afterId, chunkSize);
        if (chunk.isEmpty()) {
            return OptionalInt.empty();
        }
        double[] balancesBefore = chunk.stream().mapToDouble(TimeDeposit::getBalance).toArray();
        calculator.updateBalance(chunk);
        // Only deposits that received interest are marked; the others (e.g. still in a grace period)
        // stay due and get their first interest on the first run after becoming eligible.
        List<TimeDeposit> changed = IntStream.range(0, chunk.size())
                .filter(i -> chunk.get(i).getBalance() != balancesBefore[i])
                .mapToObj(chunk::get)
                .toList();
        repository.saveAppliedInterest(changed, today);
        return chunk.stream().mapToInt(TimeDeposit::getId).max();
    }
}
