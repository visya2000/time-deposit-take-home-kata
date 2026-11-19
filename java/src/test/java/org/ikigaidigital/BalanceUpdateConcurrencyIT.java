package org.ikigaidigital;

import org.ikigaidigital.DepositRows.StoredRow;
import org.ikigaidigital.application.port.in.GetAllTimeDepositsUseCase;
import org.ikigaidigital.application.port.in.UpdateAllBalancesUseCase;
import org.ikigaidigital.application.port.out.TimeDepositRepository;
import org.ikigaidigital.application.port.out.TransactionRunner;
import org.ikigaidigital.domain.DueTimeDeposit;
import org.ikigaidigital.domain.InterestPosting;
import org.ikigaidigital.domain.TimeDepositWithWithdrawals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.ikigaidigital.FixedClockConfiguration.TODAY;

/**
 * Concurrency guarantees of the balance update over real PostgreSQL (PLAN.md §3 "Correctness under
 * failure and concurrency", "Locking model: writes lock, reads never block"; §6 item 5). Not
 * transactional: every chunk commits on its own, as in production. Row locks held by "another
 * transaction" come from a separate JDBC connection ({@link RowLockHolder}). Every wait is bounded
 * (Future.get / CountDownLatch.await with a timeout) and the waits stay well inside the class
 * timeout. Saves write absolute balances, so the locking itself is proven by the SKIP LOCKED and
 * external-writer tests; the parallel-runs test proves that overlapping runs finish and post once.
 * Business date fixed by {@link FixedClockConfiguration}; own context because of the chunk size.
 */
@SpringBootTest(properties = "interest.update.chunk-size=50") // many chunks, so parallel runs interleave
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class BalanceUpdateConcurrencyIT {

    private static final BigDecimal OPENING_BALANCE = new BigDecimal("1200.00");
    private static final BigDecimal AFTER_ONE_POSTING = new BigDecimal("1201.00"); // basic: 1200 * 0.01 / 12

    @Autowired
    private UpdateAllBalancesUseCase updateAllBalances;

    @Autowired
    private GetAllTimeDepositsUseCase getAllTimeDeposits;

    @Autowired
    private TimeDepositRepository repository;

    @Autowired
    private TransactionRunner transactionRunner;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    private DepositRows rows;

    private ExecutorService executor;

    @BeforeEach
    void createHelpers() {
        rows = new DepositRows(jdbc);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopExecutorAndDeleteRows() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);
        rows.deleteAll();
    }

    @Test
    void twoParallelRuns_overlap_bothComplete_andPostInterestOncePerEligibleDeposit() throws Exception {
        int total = 3000;
        List<Object[]> rows = new ArrayList<>();
        for (int id = 1; id <= total; id++) {
            if (id % 20 == 0) {
                rows.add(new Object[]{id, "gold", 400, OPENING_BALANCE});  // unknown plan
            } else if (id % 10 == 0) {
                rows.add(new Object[]{id, "basic", 30, OPENING_BALANCE});  // grace period
            } else {
                rows.add(new Object[]{id, "basic", 31, OPENING_BALANCE});  // eligible
            }
        }
        jdbc.batchUpdate("INSERT INTO time_deposits (id, plan_type, days, balance) VALUES (?, ?, ?, ?)", rows);
        int ineligible = total / 10;
        int eligible = total - ineligible;

        CountDownLatch start = new CountDownLatch(1);
        Future<RunInterval> first = executor.submit(() -> awaitThenRun(start));
        Future<RunInterval> second = executor.submit(() -> awaitThenRun(start));
        start.countDown();
        RunInterval firstRun = first.get(20, TimeUnit.SECONDS); // rethrows any exception of the run
        RunInterval secondRun = second.get(20, TimeUnit.SECONDS);

        assertThat(firstRun.overlaps(secondRun)).as("the two runs overlapped in time").isTrue();

        assertThat(countRows("balance = ? AND last_interest_applied_on = ?", AFTER_ONE_POSTING, TODAY))
                .as("eligible deposits posted once, marked today").isEqualTo(eligible);
        assertThat(countRows("balance = ? AND last_interest_applied_on IS NULL", OPENING_BALANCE))
                .as("ineligible deposits unchanged and unmarked").isEqualTo(ineligible);
        assertThat(countRows("TRUE")).isEqualTo(total);
    }

    @Test
    void getIsNotBlocked_whileAnotherTransactionHoldsRowLocks() throws Exception {
        insertDeposit(1, "1200.00");
        insertDeposit(2, "500.00");

        try (RowLockHolder ignored = RowLockHolder.execute(dataSource,
                "SELECT id FROM time_deposits FOR UPDATE",
                "UPDATE time_deposits SET balance = 9999.99 WHERE id = 1")) { // never committed

            Future<List<TimeDepositWithWithdrawals>> pendingRead = executor.submit(getAllTimeDeposits::getAll);

            assertThat(pendingRead.get(5, TimeUnit.SECONDS))
                    .extracting(TimeDepositWithWithdrawals::id, TimeDepositWithWithdrawals::balance)
                    .containsExactly(
                            tuple(1, new BigDecimal("1200.00")),
                            tuple(2, new BigDecimal("500.00")));
        }
    }

    @Test
    void rowLockedByAnotherTransaction_isSkipped_andProcessedByTheNextRun() throws Exception {
        insertDeposit(1, "1200.00");
        insertDeposit(2, "1200.00");
        insertDeposit(3, "1200.00");

        try (RowLockHolder ignored = RowLockHolder.execute(dataSource,
                "SELECT id FROM time_deposits WHERE id = 2 FOR UPDATE")) {
            // Bounded: without SKIP LOCKED the run would wait for the lock and time out here.
            executor.submit(updateAllBalances::updateAllBalances).get(10, TimeUnit.SECONDS);

            assertThat(rows.storedRows()).containsExactly(
                    new StoredRow(1, AFTER_ONE_POSTING, TODAY),
                    new StoredRow(2, OPENING_BALANCE, null),
                    new StoredRow(3, AFTER_ONE_POSTING, TODAY));
        } // lock released (rolled back)

        updateAllBalances.updateAllBalances(); // same business date

        assertThat(rows.storedRows()).containsExactly(
                new StoredRow(1, AFTER_ONE_POSTING, TODAY),
                new StoredRow(2, AFTER_ONE_POSTING, TODAY),
                new StoredRow(3, AFTER_ONE_POSTING, TODAY));
    }

    @Test
    void externalBalanceUpdate_onALockedRow_waitsForTheChunkCommit_andAppliesOnTop() throws Exception {
        insertDeposit(1, "1200.00");

        Future<Integer> externalUpdate = transactionRunner.inTransaction(() -> {
            List<DueTimeDeposit> chunk = repository.lockNextDue(TODAY.minusMonths(1), 0, 10);
            assertThat(chunk).extracting(due -> due.deposit().getId()).containsExactly(1);

            // Another writer (own connection, auto-commit) touches the row this chunk holds.
            Future<Integer> pendingUpdate = executor.submit(
                    () -> jdbc.update("UPDATE time_deposits SET balance = balance - 100 WHERE id = 1"));
            // Proving "still blocked" needs a short bounded wait: not done after 500 ms means it
            // waits for our row lock (an unblocked single-row UPDATE takes a few ms).
            awaitQuietly(pendingUpdate, 500);
            assertThat(pendingUpdate).as("external update waits for the chunk's row lock").isNotDone();

            repository.saveAppliedInterest(List.of(new InterestPosting(1, AFTER_ONE_POSTING, TODAY)));
            return pendingUpdate;
        }); // chunk committed here

        assertThat(externalUpdate.get(5, TimeUnit.SECONDS)).as("rows updated externally").isEqualTo(1);
        // No lost update: the external UPDATE re-reads the committed row (1201.00) and applies on top.
        assertThat(rows.storedRows()).containsExactly(new StoredRow(1, new BigDecimal("1101.00"), TODAY));
    }

    private record RunInterval(long startNanos, long endNanos) {

        boolean overlaps(RunInterval other) {
            return startNanos < other.endNanos && other.startNanos < endNanos;
        }
    }

    private RunInterval awaitThenRun(CountDownLatch start) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("start signal not received");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        long startNanos = System.nanoTime();
        updateAllBalances.updateAllBalances();
        return new RunInterval(startNanos, System.nanoTime());
    }

    private static void awaitQuietly(Future<?> future, long millis) {
        try {
            future.get(millis, TimeUnit.MILLISECONDS);
        } catch (Exception expected) {
            // TimeoutException is the expected outcome; the caller asserts on isDone().
        }
    }

    private int countRows(String condition, Object... args) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM time_deposits WHERE " + condition, Integer.class, args);
    }

    /** A basic deposit, never posted, first eligible today (age 31). */
    private void insertDeposit(int id, String balance) {
        rows.insertDeposit(id, "basic", 31, balance);
    }

    /**
     * "Another transaction": its own connection, outside Spring's transaction management, running
     * the given statements without committing. Its locks are held until {@link #close()} rolls back.
     */
    private static final class RowLockHolder implements AutoCloseable {

        private final Connection connection;

        private RowLockHolder(Connection connection) {
            this.connection = connection;
        }

        static RowLockHolder execute(DataSource dataSource, String... statements) throws SQLException {
            Connection connection = dataSource.getConnection();
            try {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    for (String sql : statements) {
                        statement.execute(sql);
                    }
                }
                return new RowLockHolder(connection);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                connection.close();
                throw e;
            }
        }

        @Override
        public void close() throws SQLException {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }
}
