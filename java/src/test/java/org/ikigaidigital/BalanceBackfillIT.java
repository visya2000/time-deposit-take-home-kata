package org.ikigaidigital;

import org.ikigaidigital.application.port.in.UpdateAllBalancesUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Catch-up after missed runs, wired with the real adapters over PostgreSQL (PLAN.md §3
 * "Backfilling missed months", step 16): the marker read from the database drives the owed due
 * dates, and each deposit's postings are written with its own last due date. Not transactional:
 * every chunk commits on its own, as in production. Chunk size 2 over three deposits gives chunks
 * {1, 2}, {3}. Expected values are derived by hand in TimeDepositServiceTest.Backfill.
 */
@SpringBootTest(properties = "interest.update.chunk-size=2")
@Import(TestcontainersConfiguration.class)
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class BalanceBackfillIT {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    @Autowired
    private UpdateAllBalancesUseCase updateAllBalances;

    @Autowired
    private JdbcTemplate jdbc;

    @TestBean
    private Clock clock;

    static Clock clock() {
        return Clock.fixed(TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    @BeforeEach
    void insertDeposits() {
        // Last posted 2026-08-19: due 09-19, 10-19, 11-19; basic 1 % / 12 compounded in cents:
        // 120000.00 -> 120100.00 -> 120200.08 -> 120300.25.
        insertDeposit(1, "basic", 400, "120000.00", LocalDate.of(2026, 8, 19));
        // Never posted, opened 2026-08-19: age 31 on 09-19, then 10-19, 11-19 -> 1203.00.
        insertDeposit(2, "basic", 92, "1200.00", null);
        // Never posted, opened 2026-09-03: age 46 on 10-19 (5.00), then 11-19 (5.02) -> 1210.02.
        insertDeposit(3, "premium", 77, "1200.00", null);
    }

    @AfterEach
    void deleteDeposits() {
        jdbc.update("DELETE FROM time_deposits");
    }

    @Test
    void lateRun_postsEveryOwedMonth_andMarksEachDepositWithItsLastDueDate() {
        updateAllBalances.updateAllBalances();

        assertThat(storedRows()).containsExactly(
                new StoredRow(1, new BigDecimal("120300.25"), TODAY),
                new StoredRow(2, new BigDecimal("1203.00"), TODAY),
                new StoredRow(3, new BigDecimal("1210.02"), TODAY));
    }

    @Test
    void secondRun_changesNothing() {
        updateAllBalances.updateAllBalances();
        List<StoredRow> afterFirstRun = storedRows();
        assertThat(afterFirstRun.get(0).balance()).as("precondition: first run back-filled")
                .isEqualTo(new BigDecimal("120300.25"));

        updateAllBalances.updateAllBalances();

        assertThat(storedRows()).isEqualTo(afterFirstRun);
    }

    private record StoredRow(int id, BigDecimal balance, LocalDate lastInterestAppliedOn) {
    }

    private List<StoredRow> storedRows() {
        return jdbc.query("SELECT id, balance, last_interest_applied_on FROM time_deposits ORDER BY id",
                (rs, rowNum) -> new StoredRow(rs.getInt("id"), rs.getBigDecimal("balance"),
                        rs.getObject("last_interest_applied_on", LocalDate.class)));
    }

    private void insertDeposit(int id, String planType, int days, String balance, LocalDate lastApplied) {
        jdbc.update("INSERT INTO time_deposits (id, plan_type, days, balance, last_interest_applied_on)"
                        + " VALUES (?, ?, ?, ?, ?)",
                id, planType, days, new BigDecimal(balance), lastApplied);
    }
}
