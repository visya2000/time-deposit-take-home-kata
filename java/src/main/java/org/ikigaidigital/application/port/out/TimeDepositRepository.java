package org.ikigaidigital.application.port.out;

import org.ikigaidigital.TimeDeposit;
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
     * Locks and returns at most {@code limit} due deposits with {@code id > afterId}, ordered by id.
     * Due: interest never applied, or last applied on or before {@code cutoff}. Rows locked by
     * another transaction are skipped. Must run inside a transaction ({@link TransactionRunner}).
     */
    List<TimeDeposit> lockNextDue(LocalDate cutoff, int afterId, int limit);

    /**
     * Persists the balance of the given deposits (locked in the same transaction) and records
     * {@code appliedOn} as the date interest was last applied to them.
     */
    void saveAppliedInterest(List<TimeDeposit> deposits, LocalDate appliedOn);
}
