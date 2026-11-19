package org.ikigaidigital;

import org.ikigaidigital.DepositRows.StoredRow;
import org.ikigaidigital.application.port.in.UpdateAllBalancesUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ikigaidigital.FixedClockConfiguration.TODAY;

/**
 * The balance-update use case wired with the real adapters over PostgreSQL (PLAN.md §3), on the
 * fixed business date of {@link FixedClockConfiguration}. Not transactional: every chunk commits on
 * its own, as in production. Each nested class inserts its own deposits; chunk size 2. The timeout
 * keeps a non-terminating run from hanging the build. Same configuration as
 * BalanceUpdateRollbackIT, so the two share one context and container.
 */
@SpringBootTest(properties = "interest.update.chunk-size=2")
@Import({TestcontainersConfiguration.class, FixedClockConfiguration.class})
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class BalanceUpdateIT {

    @Autowired
    private UpdateAllBalancesUseCase updateAllBalances;

    @Autowired
    private JdbcTemplate jdbc;

    private DepositRows rows;

    @BeforeEach
    void createRowAccess() {
        rows = new DepositRows(jdbc);
    }

    @AfterEach
    void deleteDeposits() {
        rows.deleteAll();
    }

    /** One deposit per eligibility case; five deposits give chunks {1, 2}, {3, 4}, {5}. */
    @Nested
    class EligibilityCases {

        @BeforeEach
        void insertDeposits() {
            rows.insertDeposit(1, "basic", 31, "1000.10");    // 0.8334... -> 0.83
            rows.insertDeposit(2, "student", 365, "1200.00"); // never posted, eligible since day 31: back-filled
            rows.insertDeposit(3, "basic", 30, "1200.00");    // grace period
            rows.insertDeposit(4, "premium", 46, "1200.00");  // first day with interest
            rows.insertDeposit(5, "gold", 400, "1200.00");    // unknown plan
        }

        // Deposits 1 and 4 become eligible today: one posting, marker today. Deposit 2 (step 16) was
        // opened 2025-11-19 and first eligible at age 31 on 2025-12-20: 11 monthly postings up to
        // 2026-10-20 (age 335; 2026-11-20 is after today), student 3 % / 12 compounded in cents:
        // 1200.00 -> 1203.00 -> 1206.01 -> 1209.03 -> 1212.05 -> 1215.08 -> 1218.12 -> 1221.17
        // -> 1224.22 -> 1227.28 -> 1230.35 -> 1233.43; marker = the last due date, 2026-10-20.
        @Test
        void appliesInterestToEligibleDeposits_andMarksOnlyThem_withTheirLastDueDate() {
            updateAllBalances.updateAllBalances();

            assertThat(rows.storedRows()).containsExactly(
                    new StoredRow(1, new BigDecimal("1000.93"), TODAY),
                    new StoredRow(2, new BigDecimal("1233.43"), LocalDate.of(2026, 10, 20)),
                    new StoredRow(3, new BigDecimal("1200.00"), null),
                    new StoredRow(4, new BigDecimal("1205.00"), TODAY),
                    new StoredRow(5, new BigDecimal("1200.00"), null));
        }

        @Test
        void secondRun_changesNothing() {
            updateAllBalances.updateAllBalances();
            List<StoredRow> afterFirstRun = rows.storedRows();
            assertThat(afterFirstRun.get(0).balance()).as("precondition: first run applied interest")
                    .isEqualTo(new BigDecimal("1000.93"));

            updateAllBalances.updateAllBalances();

            assertThat(rows.storedRows()).isEqualTo(afterFirstRun);
        }
    }

    /**
     * Catch-up after missed runs (PLAN.md §3 "Backfilling missed months", step 16): the marker read
     * from the database drives the owed due dates, and each deposit's postings are written with its
     * own last due date. Three deposits give chunks {1, 2}, {3}. Expected values are derived by hand
     * in TimeDepositServiceTest.Backfill.
     */
    @Nested
    class MissedMonths {

        @BeforeEach
        void insertDeposits() {
            // Last posted 2026-08-19: due 09-19, 10-19, 11-19; basic 1 % / 12 compounded in cents:
            // 120000.00 -> 120100.00 -> 120200.08 -> 120300.25.
            rows.insertDeposit(1, "basic", 400, "120000.00", LocalDate.of(2026, 8, 19));
            // Never posted, opened 2026-08-19: age 31 on 09-19, then 10-19, 11-19 -> 1203.00.
            rows.insertDeposit(2, "basic", 92, "1200.00");
            // Never posted, opened 2026-09-03: age 46 on 10-19 (5.00), then 11-19 (5.02) -> 1210.02.
            rows.insertDeposit(3, "premium", 77, "1200.00");
        }

        @Test
        void lateRun_postsEveryOwedMonth_andMarksEachDepositWithItsLastDueDate() {
            updateAllBalances.updateAllBalances();

            assertThat(rows.storedRows()).containsExactly(
                    new StoredRow(1, new BigDecimal("120300.25"), TODAY),
                    new StoredRow(2, new BigDecimal("1203.00"), TODAY),
                    new StoredRow(3, new BigDecimal("1210.02"), TODAY));
        }

        @Test
        void secondRun_changesNothing() {
            updateAllBalances.updateAllBalances();
            List<StoredRow> afterFirstRun = rows.storedRows();
            assertThat(afterFirstRun.get(0).balance()).as("precondition: first run back-filled")
                    .isEqualTo(new BigDecimal("120300.25"));

            updateAllBalances.updateAllBalances();

            assertThat(rows.storedRows()).isEqualTo(afterFirstRun);
        }
    }
}
