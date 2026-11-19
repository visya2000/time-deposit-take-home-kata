package org.ikigaidigital.adapter.out.persistence;

import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.application.port.out.TimeDepositRepository;
import org.ikigaidigital.domain.TimeDepositWithWithdrawals;
import org.ikigaidigital.domain.Withdrawal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Component
class TimeDepositPersistenceAdapter implements TimeDepositRepository {

    private final TimeDepositJpaRepository jpaRepository;

    TimeDepositPersistenceAdapter(TimeDepositJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    // Read-only: no dirty-checking snapshots. Entities are mapped inside the transaction, so no
    // lazy load can happen afterwards (with open-in-view=false it would throw).
    @Override
    @Transactional(readOnly = true)
    public List<TimeDepositWithWithdrawals> findAllWithWithdrawals() {
        return toReadModels(jpaRepository.findAllWithWithdrawals());
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeDepositWithWithdrawals> findPageWithWithdrawals(int afterId, int limit) {
        return toReadModels(jpaRepository.findPageWithWithdrawals(afterId, limit));
    }

    @Override
    public List<TimeDeposit> lockNextDue(LocalDate cutoff, int afterId, int limit) {
        throw new UnsupportedOperationException("balance update adapter: PLAN.md step 10");
    }

    @Override
    public void saveAppliedInterest(List<TimeDeposit> deposits, LocalDate appliedOn) {
        throw new UnsupportedOperationException("balance update adapter: PLAN.md step 10");
    }

    private static List<TimeDepositWithWithdrawals> toReadModels(List<TimeDepositEntity> entities) {
        return entities.stream()
                .map(deposit -> new TimeDepositWithWithdrawals(
                        deposit.getId(), deposit.getPlanType(), deposit.getBalance(), deposit.getDays(),
                        deposit.getWithdrawals().stream()
                                .map(w -> new Withdrawal(w.getId(), w.getAmount(), w.getDate()))
                                .toList()))
                .toList();
    }
}
