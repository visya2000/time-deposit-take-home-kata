package org.ikigaidigital.adapter.out.persistence;

import org.assertj.core.groups.Tuple;
import org.ikigaidigital.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The `local` profile loads demo data (db/seed) for manual testing via Swagger. Only what PLAN.md
 * §3 "Other" promises is asserted; exact balances are free to change. Own Spring context (different
 * profile) and therefore its own database; each test is rolled back.
 */
@SpringBootTest
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
@Transactional
class SeedDataIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void seed_containsTheMainBoundariesOfEveryPlan() {
        List<Tuple> planAndDays = jdbc.query(
                "SELECT plan_type, days FROM time_deposits",
                (rs, rowNum) -> tuple(rs.getString("plan_type"), rs.getInt("days")));

        assertThat(planAndDays).contains(
                tuple("basic", 30), tuple("basic", 31),
                tuple("student", 31), tuple("student", 365), tuple("student", 366),
                tuple("premium", 45), tuple("premium", 46));
    }

    @Test
    void seed_containsADepositWithAnUnknownPlan() {
        Integer unknownPlanDeposits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM time_deposits WHERE plan_type NOT IN ('basic', 'student', 'premium')",
                Integer.class);

        assertThat(unknownPlanDeposits).isPositive();
    }

    @Test
    void seed_containsDepositsWithAndWithoutWithdrawals() {
        Integer withWithdrawals = jdbc.queryForObject("""
                SELECT COUNT(*) FROM time_deposits td
                WHERE EXISTS (SELECT 1 FROM withdrawals w WHERE w.time_deposit_id = td.id)""", Integer.class);
        Integer withoutWithdrawals = jdbc.queryForObject("""
                SELECT COUNT(*) FROM time_deposits td
                WHERE NOT EXISTS (SELECT 1 FROM withdrawals w WHERE w.time_deposit_id = td.id)""", Integer.class);

        assertThat(withWithdrawals).isPositive();
        assertThat(withoutWithdrawals).isPositive();
    }

    // Seeds use explicit ids; without advancing the identity sequences the next insert would collide.
    @Test
    void rowsInsertedWithoutIdAfterSeeding_getIdsAboveTheSeededIds() {
        Integer maxSeededDepositId = jdbc.queryForObject("SELECT MAX(id) FROM time_deposits", Integer.class);
        Integer maxSeededWithdrawalId = jdbc.queryForObject("SELECT MAX(id) FROM withdrawals", Integer.class);

        Integer depositId = jdbc.queryForObject(
                "INSERT INTO time_deposits (plan_type, days, balance) VALUES ('basic', 31, ?) RETURNING id",
                Integer.class, new BigDecimal("100.00"));
        Integer withdrawalId = jdbc.queryForObject(
                "INSERT INTO withdrawals (time_deposit_id, amount, date) VALUES (?, ?, DATE '2026-01-15') RETURNING id",
                Integer.class, depositId, new BigDecimal("10.00"));

        assertThat(depositId).isGreaterThan(maxSeededDepositId);
        assertThat(withdrawalId).isGreaterThan(maxSeededWithdrawalId);
    }
}
