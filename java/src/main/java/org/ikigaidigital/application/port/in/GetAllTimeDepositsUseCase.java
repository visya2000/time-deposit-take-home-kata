package org.ikigaidigital.application.port.in;

import org.ikigaidigital.domain.TimeDepositPage;
import org.ikigaidigital.domain.TimeDepositWithWithdrawals;

import java.util.List;

public interface GetAllTimeDepositsUseCase {

    /** All deposits with their withdrawals, ordered by id. */
    List<TimeDepositWithWithdrawals> getAll();

    /** Up to {@code limit} deposits with {@code id > afterId}, ordered by id. */
    TimeDepositPage getPage(int afterId, int limit);
}
