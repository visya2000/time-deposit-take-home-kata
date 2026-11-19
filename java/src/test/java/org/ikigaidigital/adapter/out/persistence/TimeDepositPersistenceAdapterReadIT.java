package org.ikigaidigital.adapter.out.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.ikigaidigital.TestcontainersConfiguration;
import org.ikigaidigital.application.port.out.TimeDepositRepository;
import org.ikigaidigital.domain.TimeDepositWithWithdrawals;
import org.ikigaidigital.domain.Withdrawal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Read side of the persistence adapter (PLAN.md §3 "GET endpoint", §6 item 4). Test data is
 * inserted with JdbcTemplate and the persistence context is cleared before each call, so every
 * result really comes from the query under test. Hibernate statistics are enabled only here (own
 * Spring context); each test is rolled back. The adapter's read-only transaction joins the test
 * transaction, so its boundary is not exercised here; the single-statement tests are the guard
 * against lazy loading (a dropped fetch join would issue further statements).
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(TestcontainersConfiguration.class)
@Transactional
class TimeDepositPersistenceAdapterReadIT {

    private static final LocalDate JAN_15 = LocalDate.of(2026, 1, 15);
    private static final LocalDate FEB_20 = LocalDate.of(2026, 2, 20);
    private static final LocalDate MAR_05 = LocalDate.of(2026, 3, 5);

    @Autowired
    private TimeDepositRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    // --- mapping ---------------------------------------------------------------------------

    @Test
    void findAll_mapsAllDepositAndWithdrawalFields_withMoneyAtScale2() {
        insertDeposit(7, "premium", 46, "1000.1");
        insertWithdrawal(3, 7, "50.5", FEB_20);

        List<TimeDepositWithWithdrawals> deposits = findAll();

        // BigDecimal equality includes the scale: 1000.1 or 50.5 would fail.
        assertThat(deposits).containsExactly(new TimeDepositWithWithdrawals(
                7, "premium", new BigDecimal("1000.10"), 46,
                List.of(new Withdrawal(3, new BigDecimal("50.50"), FEB_20))));
    }

    @Test
    void findAll_onEmptyTable_returnsEmptyList() {
        assertThat(findAll()).isEmpty();
    }

    @Test
    void findAll_depositWithoutWithdrawals_hasEmptyWithdrawalList() {
        insertDeposit(1, "basic", 31, "100.00");

        List<TimeDepositWithWithdrawals> deposits = findAll();

        assertThat(deposits).singleElement()
                .satisfies(deposit -> assertThat(deposit.withdrawals()).isEmpty());
    }

    // --- grouping and ordering -------------------------------------------------------------

    @Test
    void findAll_returnsDepositsOrderedById_whenInsertedOutOfOrder() {
        insertDeposit(30, "basic", 31, "300.00");
        insertDeposit(10, "student", 40, "100.00");
        insertDeposit(20, "premium", 50, "200.00");

        assertThat(findAll()).extracting(TimeDepositWithWithdrawals::id).containsExactly(10, 20, 30);
    }

    @Test
    void findAll_groupsWithdrawalsPerDeposit_orderedByWithdrawalId() {
        insertDeposit(1, "basic", 31, "100.00");
        insertDeposit(2, "premium", 46, "200.00");
        insertDeposit(3, "student", 100, "300.00");
        // Out of id order and interleaved across deposits; dates deliberately not in id order.
        insertWithdrawal(15, 2, "15.00", JAN_15);
        insertWithdrawal(12, 1, "12.00", MAR_05);
        insertWithdrawal(11, 2, "11.00", MAR_05);
        insertWithdrawal(14, 1, "14.00", JAN_15);
        insertWithdrawal(13, 2, "13.00", FEB_20);

        List<TimeDepositWithWithdrawals> deposits = findAll();

        assertThat(deposits).extracting(TimeDepositWithWithdrawals::id).containsExactly(1, 2, 3);
        assertThat(deposits.get(0).withdrawals()).containsExactly(
                new Withdrawal(12, new BigDecimal("12.00"), MAR_05),
                new Withdrawal(14, new BigDecimal("14.00"), JAN_15));
        assertThat(deposits.get(1).withdrawals()).containsExactly(
                new Withdrawal(11, new BigDecimal("11.00"), MAR_05),
                new Withdrawal(13, new BigDecimal("13.00"), FEB_20),
                new Withdrawal(15, new BigDecimal("15.00"), JAN_15));
        assertThat(deposits.get(2).withdrawals()).isEmpty();
    }

    @Test
    void findPage_groupsWithdrawalsPerDeposit_orderedByWithdrawalId() {
        insertDeposit(1, "basic", 31, "100.00");
        insertDeposit(2, "premium", 46, "200.00");
        insertWithdrawal(22, 1, "22.00", JAN_15);
        insertWithdrawal(21, 2, "21.00", FEB_20);
        insertWithdrawal(20, 1, "20.00", MAR_05);

        List<TimeDepositWithWithdrawals> page = findPage(0, 2);

        assertThat(page).extracting(TimeDepositWithWithdrawals::id).containsExactly(1, 2);
        assertThat(page.get(0).withdrawals()).extracting(Withdrawal::id).containsExactly(20, 22);
        assertThat(page.get(1).withdrawals()).extracting(Withdrawal::id).containsExactly(21);
    }

    // --- one SQL statement per call (snapshot guarantee, no N+1) ----------------------------

    @Test
    void findAll_executesExactlyOneSqlStatement() {
        insertDepositsWithWithdrawals(3, 3);

        List<TimeDepositWithWithdrawals> deposits = countingStatements(this::findAll);

        assertThat(deposits).hasSize(3);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void findPage_executesExactlyOneSqlStatement() {
        insertDepositsWithWithdrawals(4, 3);

        List<TimeDepositWithWithdrawals> page = countingStatements(() -> findPage(0, 3));

        assertThat(page).hasSize(3);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    // --- pagination --------------------------------------------------------------------------

    // LIMIT 3 on the joined rows would return only deposit 1 with 3 of its 5 withdrawals.
    @Test
    void findPage_holdsExactlyLimitDeposits_evenWhenEachHasManyWithdrawals() {
        insertDepositsWithWithdrawals(5, 5);

        List<TimeDepositWithWithdrawals> page = findPage(0, 3);

        assertThat(page).extracting(TimeDepositWithWithdrawals::id).containsExactly(1, 2, 3);
        assertThat(page).allSatisfy(deposit -> assertThat(deposit.withdrawals()).hasSize(5));
    }

    @Test
    void findPage_startsAfterTheGivenId_excludingIt() {
        insertDeposits(10, 20, 35, 50, 70);

        assertThat(findPage(20, 2)).extracting(TimeDepositWithWithdrawals::id).containsExactly(35, 50);
    }

    @Test
    void findPage_afterIdBetweenExistingIds_startsAtTheNextHigherId() {
        insertDeposits(10, 20, 35, 50);

        assertThat(findPage(21, 2)).extracting(TimeDepositWithWithdrawals::id).containsExactly(35, 50);
    }

    @Test
    void findPage_returnsTheRest_whenFewerThanLimitRemain() {
        insertDeposits(10, 20, 35, 50);

        assertThat(findPage(20, 5)).extracting(TimeDepositWithWithdrawals::id).containsExactly(35, 50);
    }

    @Test
    void findPage_returnsExactlyLimit_whenExactlyLimitRemain() {
        insertDeposits(10, 20, 35);

        assertThat(findPage(10, 2)).extracting(TimeDepositWithWithdrawals::id).containsExactly(20, 35);
    }

    @Test
    void findPage_afterIdBeyondMaxId_returnsEmptyList() {
        insertDeposits(10, 20);

        assertThat(findPage(20, 5)).isEmpty();
        assertThat(findPage(1_000, 5)).isEmpty();
    }

    @Test
    void walkingAllPages_yieldsEveryDepositExactlyOnce_inIdOrder() {
        insertDeposits(3, 8, 9, 15, 21, 22, 40);

        List<Integer> walked = new ArrayList<>();
        int afterId = 0;
        List<TimeDepositWithWithdrawals> page;
        while (!(page = findPage(afterId, 3)).isEmpty()) {
            assertThat(page).hasSizeLessThanOrEqualTo(3);
            page.forEach(deposit -> walked.add(deposit.id()));
            afterId = page.get(page.size() - 1).id();
        }

        assertThat(walked).containsExactly(3, 8, 9, 15, 21, 22, 40);
    }

    // --- helpers -----------------------------------------------------------------------------

    private List<TimeDepositWithWithdrawals> findAll() {
        entityManager.clear();
        return repository.findAllWithWithdrawals();
    }

    private List<TimeDepositWithWithdrawals> findPage(int afterId, int limit) {
        entityManager.clear();
        return repository.findPageWithWithdrawals(afterId, limit);
    }

    private <T> T countingStatements(Supplier<T> call) {
        entityManager.clear();
        statistics.clear();
        return call.get();
    }

    private void insertDeposits(int... ids) {
        for (int id : ids) {
            insertDeposit(id, "basic", 31, "100.00");
        }
    }

    /** Deposits 1..depositCount, each with withdrawalsPerDeposit withdrawals. */
    private void insertDepositsWithWithdrawals(int depositCount, int withdrawalsPerDeposit) {
        int withdrawalId = 1;
        for (int depositId = 1; depositId <= depositCount; depositId++) {
            insertDeposit(depositId, "basic", 31, "1000.00");
            for (int i = 0; i < withdrawalsPerDeposit; i++) {
                insertWithdrawal(withdrawalId++, depositId, "10.00", JAN_15);
            }
        }
    }

    private void insertDeposit(int id, String planType, int days, String balance) {
        jdbc.update("INSERT INTO time_deposits (id, plan_type, days, balance) VALUES (?, ?, ?, ?)",
                id, planType, days, new BigDecimal(balance));
    }

    private void insertWithdrawal(int id, int depositId, String amount, LocalDate date) {
        jdbc.update("INSERT INTO withdrawals (id, time_deposit_id, amount, date) VALUES (?, ?, ?, ?)",
                id, depositId, new BigDecimal(amount), date);
    }
}
