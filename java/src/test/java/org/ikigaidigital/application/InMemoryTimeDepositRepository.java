package org.ikigaidigital.application;

import org.ikigaidigital.application.port.out.TimeDepositRepository;
import org.ikigaidigital.domain.TimeDepositWithWithdrawals;

import java.util.List;
import java.util.TreeMap;

/** In-memory fake of the outbound repository port; deposits are kept ordered by id. */
class InMemoryTimeDepositRepository implements TimeDepositRepository {

    private final TreeMap<Integer, TimeDepositWithWithdrawals> deposits = new TreeMap<>();

    InMemoryTimeDepositRepository(TimeDepositWithWithdrawals... deposits) {
        for (TimeDepositWithWithdrawals deposit : deposits) {
            this.deposits.put(deposit.id(), deposit);
        }
    }

    @Override
    public List<TimeDepositWithWithdrawals> findAllWithWithdrawals() {
        return List.copyOf(deposits.values());
    }

    @Override
    public List<TimeDepositWithWithdrawals> findPageWithWithdrawals(int afterId, int limit) {
        return deposits.tailMap(afterId, false).values().stream()
                .limit(limit)
                .toList();
    }
}
