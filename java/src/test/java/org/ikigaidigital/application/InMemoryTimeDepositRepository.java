package org.ikigaidigital.application;

import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.application.port.out.TimeDepositRepository;
import org.ikigaidigital.domain.TimeDepositWithWithdrawals;
import org.ikigaidigital.domain.Withdrawal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * In-memory fake of the outbound repository port that behaves like the database: rows are kept
 * ordered by id, {@link #lockNextDue} returns fresh copies, and only {@link #saveAppliedInterest}
 * writes back (balance rounded to cents like the persistence adapter). Transactions are simulated
 * by {@link InMemoryTransactionRunner} with a snapshot; the update methods fail outside of one.
 */
class InMemoryTimeDepositRepository implements TimeDepositRepository {

    private record Row(int id, String planType, BigDecimal balance, int days,
                       LocalDate lastInterestAppliedOn, List<Withdrawal> withdrawals) {

        TimeDepositWithWithdrawals toReadModel() {
            return new TimeDepositWithWithdrawals(id, planType, balance, days, withdrawals);
        }
    }

    private TreeMap<Integer, Row> rows = new TreeMap<>();
    private TreeMap<Integer, Row> snapshot; // non-null while a transaction is open
    private final Set<Integer> lockedIds = new HashSet<>();

    /** Deposits whose interest was never applied. */
    InMemoryTimeDepositRepository(TimeDepositWithWithdrawals... deposits) {
        for (TimeDepositWithWithdrawals d : deposits) {
            rows.put(d.id(), new Row(d.id(), d.planType(), d.balance(), d.days(), null, d.withdrawals()));
        }
    }

    /** Adds a deposit without withdrawals whose interest was never applied. */
    void add(int id, String planType, String balance, int days) {
        add(id, planType, balance, days, null);
    }

    void add(int id, String planType, String balance, int days, LocalDate lastInterestAppliedOn) {
        rows.put(id, new Row(id, planType, new BigDecimal(balance), days, lastInterestAppliedOn, List.of()));
    }

    BigDecimal balanceOf(int id) {
        return rows.get(id).balance();
    }

    LocalDate lastInterestAppliedOn(int id) {
        return rows.get(id).lastInterestAppliedOn();
    }

    /** Simulates the external system that ages deposits between runs. */
    void setDays(int id, int days) {
        Row row = rows.get(id);
        rows.put(id, new Row(id, row.planType(), row.balance(), days, row.lastInterestAppliedOn(), row.withdrawals()));
    }

    void beginTransaction() {
        if (snapshot != null) {
            throw new IllegalStateException("transaction already open");
        }
        snapshot = new TreeMap<>(rows);
    }

    void commit() {
        snapshot = null;
        lockedIds.clear();
    }

    void rollback() {
        rows = snapshot;
        commit();
    }

    @Override
    public List<TimeDepositWithWithdrawals> findAllWithWithdrawals() {
        return rows.values().stream().map(Row::toReadModel).toList();
    }

    @Override
    public List<TimeDepositWithWithdrawals> findPageWithWithdrawals(int afterId, int limit) {
        return rows.tailMap(afterId, false).values().stream()
                .limit(limit)
                .map(Row::toReadModel)
                .toList();
    }

    @Override
    public List<TimeDeposit> lockNextDue(LocalDate cutoff, int afterId, int limit) {
        requireTransaction();
        List<TimeDeposit> due = rows.tailMap(afterId, false).values().stream()
                .filter(row -> row.lastInterestAppliedOn() == null || !row.lastInterestAppliedOn().isAfter(cutoff))
                .limit(limit)
                .map(row -> new TimeDeposit(row.id(), row.planType(), row.balance().doubleValue(), row.days()))
                .toList();
        due.forEach(deposit -> lockedIds.add(deposit.getId()));
        return due;
    }

    @Override
    public void saveAppliedInterest(List<TimeDeposit> deposits, LocalDate appliedOn) {
        requireTransaction();
        for (TimeDeposit deposit : deposits) {
            if (!lockedIds.contains(deposit.getId())) {
                throw new IllegalStateException("deposit " + deposit.getId() + " not locked in this transaction");
            }
            Row row = rows.get(deposit.getId());
            BigDecimal balance = BigDecimal.valueOf(deposit.getBalance()).setScale(2, RoundingMode.HALF_UP);
            rows.put(row.id(), new Row(row.id(), row.planType(), balance, row.days(), appliedOn, row.withdrawals()));
        }
    }

    private void requireTransaction() {
        if (snapshot == null) {
            throw new IllegalStateException("must run inside a transaction");
        }
    }
}
