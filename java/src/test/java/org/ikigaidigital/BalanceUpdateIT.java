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
 * The balance-update use case wired with the real adapters over PostgreSQL (PLAN.md §3). Not
 * transactional: every chunk commits on its own, as in production. Chunk size 2 over five deposits
 * gives chunks {1, 2}, {3, 4}, {5}. The timeout keeps a non-terminating run from hanging the build.
 */
@SpringBootTest(properties = "interest.update.chunk-size=2")
@Import(TestcontainersConfiguration.class)
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class BalanceUpdateIT {

    @Autowired
    private UpdateAllBalancesUseCase updateAllBalances;

    @Autowired
    private JdbcTemplate jdbc;

    // Fixed business date: a run crossing midnight cannot make the expected marker flaky.
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    @TestBean
    private Clock clock;

    static Clock clock() {
        return Clock.fixed(TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    @BeforeEach
    void insertDeposits() {
        insertDeposit(1, "basic", 31, "1000.10");    // 0.8334... -> 0.83
        insertDeposit(2, "student", 365, "1200.00"); // last day with interest
        insertDeposit(3, "basic", 30, "1200.00");    // grace period
        insertDeposit(4, "premium", 46, "1200.00");  // first day with interest
        insertDeposit(5, "gold", 400, "1200.00");    // unknown plan
    }

    @AfterEach
    void deleteDeposits() {
        jdbc.update("DELETE FROM time_deposits");
    }

    @Test
    void appliesInterestToEligibleDeposits_andMarksOnlyThem_withTodaysBusinessDate() {
        updateAllBalances.updateAllBalances();

        assertThat(storedRows()).containsExactly(
                new StoredRow(1, new BigDecimal("1000.93"), TODAY),
                new StoredRow(2, new BigDecimal("1203.00"), TODAY),
                new StoredRow(3, new BigDecimal("1200.00"), null),
                new StoredRow(4, new BigDecimal("1205.00"), TODAY),
                new StoredRow(5, new BigDecimal("1200.00"), null));
    }

    @Test
    void secondRun_changesNothing() {
        updateAllBalances.updateAllBalances();
        List<StoredRow> afterFirstRun = storedRows();
        assertThat(afterFirstRun.get(0).balance()).as("precondition: first run applied interest")
                .isEqualTo(new BigDecimal("1000.93"));

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

    private void insertDeposit(int id, String planType, int days, String balance) {
        jdbc.update("INSERT INTO time_deposits (id, plan_type, days, balance) VALUES (?, ?, ?, ?)",
                id, planType, days, new BigDecimal(balance));
    }
}
