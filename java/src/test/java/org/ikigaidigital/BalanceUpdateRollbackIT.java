package org.ikigaidigital;

import org.ikigaidigital.DepositRows.StoredRow;
import org.ikigaidigital.application.port.in.UpdateAllBalancesUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.ikigaidigital.FixedClockConfiguration.TODAY;

/**
 * A failure in the middle of a chunk, against PostgreSQL (PLAN.md §6 item 5, step 17). The
 * service-level failure test uses the in-memory fake and fails before any write; here an UPDATE has
 * already been executed in the database when the chunk fails, and the test proves PostgreSQL rolls
 * it back. Real adapters and database, only the clock is fixed ({@link FixedClockConfiguration}):
 * the failure comes from a test-created trigger, which also counts every UPDATE that reaches the
 * database in a sequence (sequences are not rolled back), so the test observes that deposit 3's
 * UPDATE was executed. Not transactional: every chunk commits on its own, as in production. Chunk
 * size 2 over six deposits gives chunks {1, 2}, {3, 4}, {5, 6}. Same configuration as
 * BalanceUpdateIT, so the two share one context and container.
 */
@SpringBootTest(properties = "interest.update.chunk-size=2")
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class BalanceUpdateRollbackIT {

    private static final int FAILING_DEPOSIT_ID = 4;
    private static final String FAILURE_MESSAGE = "simulated failure on deposit " + FAILING_DEPOSIT_ID;

    @Autowired
    private UpdateAllBalancesUseCase updateAllBalances;

    @Autowired
    private JdbcTemplate jdbc;

    private DepositRows rows;

    @BeforeEach
    void insertDepositsAndFailingTrigger() {
        rows = new DepositRows(jdbc);
        // Basic, never posted, first eligible today (age 31): exactly one posting each,
        // 1200.00 * 1 % / 12 = 1.00 -> 1201.00, marker today.
        for (int id = 1; id <= 6; id++) {
            rows.insertDeposit(id, "basic", 31, "1200.00");
        }
        jdbc.execute("DROP SEQUENCE IF EXISTS deposit_update_attempts");
        jdbc.execute("CREATE SEQUENCE deposit_update_attempts");
        jdbc.execute("CREATE OR REPLACE FUNCTION fail_deposit_update() RETURNS trigger LANGUAGE plpgsql AS $$"
                + " BEGIN PERFORM nextval('deposit_update_attempts');"
                + " IF NEW.id = " + FAILING_DEPOSIT_ID + " THEN RAISE EXCEPTION '" + FAILURE_MESSAGE + "'; END IF;"
                + " RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_deposit_update BEFORE UPDATE ON time_deposits FOR EACH ROW"
                + " EXECUTE FUNCTION fail_deposit_update()");
    }

    @AfterEach
    void dropTriggerAndDeleteDeposits() {
        dropFailingTrigger();
        jdbc.execute("DROP FUNCTION IF EXISTS fail_deposit_update()");
        jdbc.execute("DROP SEQUENCE IF EXISTS deposit_update_attempts");
        rows.deleteAll();
    }

    @Test
    void failureMidChunk_rollsBackTheWholeChunkInTheDatabase_andKeepsEarlierChunksCommitted() {
        Throwable failure = catchThrowable(updateAllBalances::updateAllBalances);

        assertThat(failure).as("the run fails at chunk {3, 4}").isNotNull()
                .rootCause().hasMessageContaining(FAILURE_MESSAGE);
        // UPDATEs that reached the database: 1 and 2 (committed), 3 (executed, then rolled back),
        // 4 (failed). Without the executed UPDATE 3 this would be 3.
        assertThat(jdbc.queryForObject("SELECT last_value FROM deposit_update_attempts", Long.class))
                .as("UPDATEs executed by PostgreSQL").isEqualTo(4);
        assertThat(rows.storedRows()).containsExactly(
                // Chunk {1, 2} committed before the failure.
                new StoredRow(1, new BigDecimal("1201.00"), TODAY),
                new StoredRow(2, new BigDecimal("1201.00"), TODAY),
                // The key assertion. At chunk {3, 4}'s commit Hibernate flushes the UPDATEs ordered by
                // id (order_updates) in one JDBC batch: UPDATE 3 is executed by PostgreSQL, then the
                // trigger fails UPDATE 4, and the transaction rolls back UPDATE 3 with it. If the chunk
                // were not atomic (e.g. each deposit saved and committed on its own), deposit 3 would
                // stay at 1201.00 here.
                new StoredRow(3, new BigDecimal("1200.00"), null),
                new StoredRow(4, new BigDecimal("1200.00"), null),
                // Chunk {5, 6} is never reached.
                new StoredRow(5, new BigDecimal("1200.00"), null),
                new StoredRow(6, new BigDecimal("1200.00"), null));
    }

    @Test
    void rerunAfterTheFailureIsFixed_postsTheRest_withoutPostingTheCommittedChunkTwice() {
        Throwable failure = catchThrowable(updateAllBalances::updateAllBalances);
        assertThat(failure).as("precondition: the first run fails at deposit 4").isNotNull()
                .rootCause().hasMessageContaining(FAILURE_MESSAGE);
        dropFailingTrigger();

        updateAllBalances.updateAllBalances();

        // Deposits 1 and 2 would be 1202.00 if their committed posting were applied again.
        assertThat(rows.storedRows()).containsExactly(
                new StoredRow(1, new BigDecimal("1201.00"), TODAY),
                new StoredRow(2, new BigDecimal("1201.00"), TODAY),
                new StoredRow(3, new BigDecimal("1201.00"), TODAY),
                new StoredRow(4, new BigDecimal("1201.00"), TODAY),
                new StoredRow(5, new BigDecimal("1201.00"), TODAY),
                new StoredRow(6, new BigDecimal("1201.00"), TODAY));
    }

    private void dropFailingTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_deposit_update ON time_deposits");
    }
}
