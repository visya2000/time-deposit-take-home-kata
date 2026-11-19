package org.ikigaidigital;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Test data access to the {@code time_deposits} table with plain SQL, bypassing the adapter under
 * test: inserts deposits, reads back what the balance update stored and deletes all rows.
 */
public class DepositRows {

    /** A stored deposit as the balance update leaves it: balance and marker. */
    public record StoredRow(int id, BigDecimal balance, LocalDate lastInterestAppliedOn) {
    }

    private final JdbcTemplate jdbc;

    public DepositRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A deposit whose interest was never applied (marker NULL). */
    public void insertDeposit(int id, String planType, int days, String balance) {
        insertDeposit(id, planType, days, balance, null);
    }

    public void insertDeposit(int id, String planType, int days, String balance, LocalDate lastInterestAppliedOn) {
        jdbc.update("INSERT INTO time_deposits (id, plan_type, days, balance, last_interest_applied_on)"
                        + " VALUES (?, ?, ?, ?, ?)",
                id, planType, days, new BigDecimal(balance), lastInterestAppliedOn);
    }

    /** All deposits, ordered by id. */
    public List<StoredRow> storedRows() {
        return jdbc.query("SELECT id, balance, last_interest_applied_on FROM time_deposits ORDER BY id",
                (rs, rowNum) -> new StoredRow(rs.getInt("id"), rs.getBigDecimal("balance"),
                        rs.getObject("last_interest_applied_on", LocalDate.class)));
    }

    /** Withdrawals first: they reference their deposit. */
    public void deleteAll() {
        jdbc.update("DELETE FROM withdrawals");
        jdbc.update("DELETE FROM time_deposits");
    }
}
