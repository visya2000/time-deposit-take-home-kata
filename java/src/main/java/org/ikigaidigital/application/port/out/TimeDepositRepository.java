package org.ikigaidigital.application.port.out;

import org.ikigaidigital.domain.TimeDepositWithWithdrawals;

import java.util.List;

public interface TimeDepositRepository {

    /** All deposits with their withdrawals (ordered by withdrawal id), ordered by deposit id. */
    List<TimeDepositWithWithdrawals> findAllWithWithdrawals();

    /**
     * At most {@code limit} deposits with {@code id > afterId} and their withdrawals, ordered as in
     * {@link #findAllWithWithdrawals()}.
     */
    List<TimeDepositWithWithdrawals> findPageWithWithdrawals(int afterId, int limit);
}
