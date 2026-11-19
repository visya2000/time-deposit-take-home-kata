package org.ikigaidigital.adapter.out.persistence;

import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.application.port.out.TimeDepositRepository;
import org.ikigaidigital.domain.TimeDepositWithWithdrawals;
import org.ikigaidigital.domain.Withdrawal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    // MANDATORY: row locks only make sense inside the caller's chunk transaction; without one,
    // FOR UPDATE would run in auto-commit and release the locks immediately.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<TimeDeposit> lockNextDue(LocalDate cutoff, int afterId, int limit) {
        return jpaRepository.lockNextDue(cutoff, afterId, limit).stream()
                .map(entity -> new TimeDeposit(entity.getId(), entity.getPlanType(),
                        entity.getBalance().doubleValue(), entity.getDays()))
                .toList();
    }

    /**
     * Updates the entities locked earlier in the same transaction (no extra SELECT); dirty checking
     * flushes only these rows on commit, as batched UPDATEs. The double balance is re-quantised to
     * cents on every write, so floating-point error never accumulates across runs.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveAppliedInterest(List<TimeDeposit> deposits, LocalDate appliedOn) {
        for (TimeDeposit deposit : deposits) {
            TimeDepositEntity entity = jpaRepository.findById(deposit.getId()).orElseThrow();
            entity.applyInterest(
                    BigDecimal.valueOf(deposit.getBalance()).setScale(2, RoundingMode.HALF_UP), appliedOn);
        }
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
