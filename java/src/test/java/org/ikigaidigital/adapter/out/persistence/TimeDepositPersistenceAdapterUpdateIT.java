package org.ikigaidigital.adapter.out.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.ikigaidigital.TestcontainersConfiguration;
import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.application.port.out.TimeDepositRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Update side of the persistence adapter (PLAN.md §3 "Run algorithm", §6 item 4). Each test runs in
 * one rolled-back transaction, which plays the role of a chunk transaction. Dirty checking writes at
 * commit, so tests flush and clear the persistence context before reading rows back with JdbcTemplate.
 * Skipping rows locked by another transaction is covered by the concurrency tests (step 11).
 * Statistics are enabled as in TimeDepositPersistenceAdapterReadIT, so the two share one context.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(TestcontainersConfiguration.class)
@Transactional
class TimeDepositPersistenceAdapterUpdateIT {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private static final LocalDate CUTOFF = TODAY.minusMonths(1); // 2026-10-19

    @Autowired
    private TimeDepositRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    // --- lockNextDue: due filter (the SQL is the only place the rule is evaluated) -----------

    @ParameterizedTest(name = "last interest applied on {0}")
    @NullSource
    @ValueSource(strings = {"2026-10-18", "2026-10-19"}) // cutoff - 1 day, cutoff
    void lockNextDue_returnsDeposit_whenInterestNeverAppliedOrLastAppliedOnOrBeforeCutoff(LocalDate lastApplied) {
        insertDeposit(1, "basic", 31, "1000.00", lastApplied);

        assertThat(ids(repository.lockNextDue(CUTOFF, 0, 10))).containsExactly(1);
    }

    @ParameterizedTest(name = "last interest applied on {0}")
    @ValueSource(strings = {"2026-10-20", "2026-11-19"}) // cutoff + 1 day, today
    void lockNextDue_skipsDeposit_whenLastInterestAppliedAfterCutoff(LocalDate lastApplied) {
        insertDeposit(1, "basic", 31, "1000.00", lastApplied);

        assertThat(repository.lockNextDue(CUTOFF, 0, 10)).isEmpty();
    }

    // --- lockNextDue: keyset cursor and limit ------------------------------------------------

    @Test
    void lockNextDue_returnsAtMostLimitDueDeposits_afterTheGivenId_orderedById() {
        // Inserted out of id order, ids not contiguous.
        insertDeposits(21, 9, 3, 15, 8);

        assertThat(ids(repository.lockNextDue(CUTOFF, 8, 2))).containsExactly(9, 15);
    }

    @Test
    void lockNextDue_afterIdBetweenExistingIds_startsAtTheNextHigherId() {
        insertDeposits(3, 8, 9, 15);

        assertThat(ids(repository.lockNextDue(CUTOFF, 4, 10))).containsExactly(8, 9, 15);
    }

    // A LIMIT applied before the due filter would return an empty chunk here and end the run early.
    @Test
    void lockNextDue_limitCountsOnlyDueDeposits() {
        insertDeposit(1, "basic", 31, "1000.00", TODAY);
        insertDeposit(2, "basic", 31, "1000.00", TODAY);
        insertDeposits(3, 4, 5);

        assertThat(ids(repository.lockNextDue(CUTOFF, 0, 2))).containsExactly(3, 4);
    }

    @Test
    void lockNextDue_afterIdAtOrBeyondHighestId_returnsEmptyList() {
        insertDeposits(3, 8);

        assertThat(repository.lockNextDue(CUTOFF, 8, 10)).isEmpty();
    }

    // --- lockNextDue: mapping ----------------------------------------------------------------

    @Test
    void lockNextDue_mapsRowToTimeDeposit_withBalanceAsDouble() {
        insertDeposit(7, "premium", 46, "1000.10", null);

        List<TimeDeposit> locked = repository.lockNextDue(CUTOFF, 0, 10);

        assertThat(locked).singleElement()
                .extracting(TimeDeposit::getId, TimeDeposit::getPlanType, TimeDeposit::getDays, TimeDeposit::getBalance)
                .containsExactly(7, "premium", 46, 1000.1);
    }

    // --- saveAppliedInterest -----------------------------------------------------------------

    @Test
    void saveAppliedInterest_writesBalanceAndMarker_onlyForTheGivenDeposits() {
        insertDeposit(1, "basic", 31, "1000.10", null);
        insertDeposit(2, "basic", 31, "500.00", null);
        insertDeposit(3, "basic", 400, "2000.00", CUTOFF);
        List<TimeDeposit> locked = repository.lockNextDue(CUTOFF, 0, 10);
        locked.forEach(deposit -> deposit.setBalance(deposit.getBalance() + 0.83));

        repository.saveAppliedInterest(List.of(locked.get(0), locked.get(2)), TODAY);
        flushAndClear();

        assertThat(storedRows()).containsExactly(
                new StoredRow(1, new BigDecimal("1000.93"), TODAY),
                new StoredRow(2, new BigDecimal("500.00"), null), // locked, changed in memory, not passed
                new StoredRow(3, new BigDecimal("2000.83"), TODAY));
    }

    // The lock returns managed entities: the save reads them from the persistence context (no
    // SELECT) and dirty checking writes only the passed deposits, in one batched statement.
    @Test
    void saveAppliedInterest_updatesOnlyThePassedLockedEntities_inOneBatchWithoutFurtherSelects() {
        insertDeposits(1, 2, 3);
        List<TimeDeposit> locked = repository.lockNextDue(CUTOFF, 0, 10);
        locked.forEach(deposit -> deposit.setBalance(deposit.getBalance() + 1));
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        repository.saveAppliedInterest(List.of(locked.get(0), locked.get(2)), TODAY);
        entityManager.flush();

        assertThat(statistics.getEntityLoadCount()).as("entities loaded from the database").isZero();
        assertThat(statistics.getEntityUpdateCount()).as("entities updated").isEqualTo(2);
        assertThat(statistics.getPrepareStatementCount()).as("SQL statements").isEqualTo(1);
    }

    // 1000.10 + 0.83 is 1000.9300000000001 in double. 1.005 pins BigDecimal.valueOf (the decimal the
    // double prints as): new BigDecimal(1.005) is 1.00499999... and would round to 1.00.
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "1000.9300000000001, 1000.93",
            "1.005,              1.01"
    })
    void saveAppliedInterest_storesBalanceRoundedHalfUpToCents(double newBalance, BigDecimal expected) {
        insertDeposit(1, "basic", 31, "1.00", null);
        TimeDeposit deposit = repository.lockNextDue(CUTOFF, 0, 10).get(0);
        deposit.setBalance(newBalance);

        repository.saveAppliedInterest(List.of(deposit), TODAY);
        flushAndClear();

        assertThat(storedRows()).containsExactly(new StoredRow(1, expected, TODAY));
    }

    // --- both methods require the caller's (chunk) transaction --------------------------------

    @Nested
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    class WithoutTransaction {

        @Test
        void lockNextDue_fails() {
            assertThatThrownBy(() -> repository.lockNextDue(CUTOFF, 0, 10))
                    .isInstanceOf(IllegalTransactionStateException.class);
        }

        @Test
        void saveAppliedInterest_fails() {
            TimeDeposit deposit = new TimeDeposit(1, "basic", 1000.83, 31);

            assertThatThrownBy(() -> repository.saveAppliedInterest(List.of(deposit), TODAY))
                    .isInstanceOf(IllegalTransactionStateException.class);
        }
    }

    // --- helpers -----------------------------------------------------------------------------

    private record StoredRow(int id, BigDecimal balance, LocalDate lastInterestAppliedOn) {
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private List<StoredRow> storedRows() {
        return jdbc.query("SELECT id, balance, last_interest_applied_on FROM time_deposits ORDER BY id",
                (rs, rowNum) -> new StoredRow(rs.getInt("id"), rs.getBigDecimal("balance"),
                        rs.getObject("last_interest_applied_on", LocalDate.class)));
    }

    private static List<Integer> ids(List<TimeDeposit> deposits) {
        return deposits.stream().map(TimeDeposit::getId).toList();
    }

    /** Due deposits (interest never applied). */
    private void insertDeposits(int... ids) {
        for (int id : ids) {
            insertDeposit(id, "basic", 31, "1000.00", null);
        }
    }

    private void insertDeposit(int id, String planType, int days, String balance, LocalDate lastApplied) {
        jdbc.update("INSERT INTO time_deposits (id, plan_type, days, balance, last_interest_applied_on)"
                        + " VALUES (?, ?, ?, ?, ?)",
                id, planType, days, new BigDecimal(balance), lastApplied);
    }
}
