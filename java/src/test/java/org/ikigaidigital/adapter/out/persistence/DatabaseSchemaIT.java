package org.ikigaidigital.adapter.out.persistence;

import org.ikigaidigital.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Flyway migration creates the planned schema (PLAN.md §4) and no seed data is loaded outside
 * the `local` profile. Each test is rolled back (the context and its database are shared with
 * other ITs).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class DatabaseSchemaIT {

    @Autowired
    private JdbcTemplate jdbc;

    // The seed is the only repeatable migration (version NULL); tests control their own data.
    @Test
    void seedData_isNotLoadedOutsideTheLocalProfile() {
        Integer repeatableMigrations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NULL", Integer.class);

        assertThat(repeatableMigrations).isZero();
    }

    @Test
    void depositInsertedWithoutId_getsGeneratedIncreasingIds() {
        int first = insertDepositWithoutId(new BigDecimal("100.00"));
        int second = insertDepositWithoutId(new BigDecimal("200.00"));

        assertThat(second).isGreaterThan(first);
    }

    // Identity BY DEFAULT (not ALWAYS): seeds and tests may still insert explicit ids.
    @Test
    void explicitIds_canBeInserted() {
        jdbc.update("INSERT INTO time_deposits (id, plan_type, days, balance) VALUES (100000, 'basic', 31, 1.00)");
        jdbc.update("INSERT INTO withdrawals (id, time_deposit_id, amount, date) "
                + "VALUES (100000, 100000, 1.00, DATE '2026-01-15')");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM withdrawals WHERE id = 100000", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void balance_isStoredWithScale2() {
        int id = insertDepositWithoutId(new BigDecimal("1000.1"));

        BigDecimal balance = jdbc.queryForObject("SELECT balance FROM time_deposits WHERE id = ?",
                BigDecimal.class, id);

        // isEqualTo on BigDecimal compares the scale too: 1000.1 would fail.
        assertThat(balance).isEqualTo(new BigDecimal("1000.10"));
    }

    @Test
    void withdrawalAmount_isStoredWithScale2() {
        int depositId = insertDepositWithoutId(new BigDecimal("1000.00"));
        jdbc.update("INSERT INTO withdrawals (time_deposit_id, amount, date) VALUES (?, ?, DATE '2026-01-15')",
                depositId, new BigDecimal("50.5"));

        BigDecimal amount = jdbc.queryForObject("SELECT amount FROM withdrawals WHERE time_deposit_id = ?",
                BigDecimal.class, depositId);

        assertThat(amount).isEqualTo(new BigDecimal("50.50"));
    }

    @Test
    void withdrawal_forANonExistentDeposit_isRejectedByTheForeignKey() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO withdrawals (time_deposit_id, amount, date) VALUES (?, ?, DATE '2026-01-15')",
                999_999, new BigDecimal("10.00")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // PostgreSQL does not index foreign keys automatically; the GET join relies on this index.
    @Test
    void withdrawalsTimeDepositId_hasAnIndexLedByThatColumn() {
        List<String> indexDefinitions = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND tablename = 'withdrawals'",
                String.class);

        assertThat(indexDefinitions).anySatisfy(definition ->
                assertThat(definition).containsPattern("\\(time_deposit_id[,)]"));
    }

    private int insertDepositWithoutId(BigDecimal balance) {
        return jdbc.queryForObject(
                "INSERT INTO time_deposits (plan_type, days, balance) VALUES ('basic', 31, ?) RETURNING id",
                Integer.class, balance);
    }
}
