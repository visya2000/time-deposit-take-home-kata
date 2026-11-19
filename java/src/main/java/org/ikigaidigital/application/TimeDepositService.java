package org.ikigaidigital.application;

import org.ikigaidigital.application.port.in.GetAllTimeDepositsUseCase;
import org.ikigaidigital.application.port.out.TimeDepositRepository;
import org.ikigaidigital.domain.TimeDepositPage;
import org.ikigaidigital.domain.TimeDepositWithWithdrawals;

import java.util.List;

public class TimeDepositService implements GetAllTimeDepositsUseCase {

    private final TimeDepositRepository repository;

    public TimeDepositService(TimeDepositRepository repository) {
        this.repository = repository;
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
}
