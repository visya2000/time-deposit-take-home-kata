package org.ikigaidigital.application.port.out;

import org.ikigaidigital.domain.DueTimeDeposit;
import org.ikigaidigital.domain.InterestPosting;
import org.ikigaidigital.domain.TimeDepositWithWithdrawals;

import java.time.LocalDate;
import java.util.List;

public interface TimeDepositRepository {

    /** All deposits with their withdrawals (ordered by withdrawal id), ordered by deposit id. */
    List<TimeDepositWithWithdrawals> findAllWithWithdrawals();

    /**
     * At most {@code limit} deposits with {@code id > afterId} and their withdrawals, ordered as in
     * {@link #findAllWithWithdrawals()}.
     */
    List<TimeDepositWithWithdrawals> findPageWithWithdrawals(int afterId, int limit);

    /**
     * Locks and returns at most {@code limit} due deposits with {@code id > afterId}, ordered by id,
     * each with the scheduled due date of its last interest posting. Due: interest never applied,
     * or last applied on or before {@code cutoff}. Rows locked by another transaction are skipped.
     * Must run inside a transaction ({@link TransactionRunner}).
     */
    List<DueTimeDeposit> lockNextDue(LocalDate cutoff, int afterId, int limit);

    /**
     * Persists each posting's balance and marker for its deposit (locked in the same transaction).
     */
    void saveAppliedInterest(List<InterestPosting> postings);
}
