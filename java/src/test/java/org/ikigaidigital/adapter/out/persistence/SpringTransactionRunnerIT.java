package org.ikigaidigital.adapter.out.persistence;

import org.ikigaidigital.TestcontainersConfiguration;
import org.ikigaidigital.application.port.out.TransactionRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Not @Transactional: the runner must own the transaction, so its commit and rollback are real and
 * the rows are checked afterwards on a new connection. Rows are deleted after each test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SpringTransactionRunnerIT {

    @Autowired
    private TransactionRunner transactionRunner;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void deleteDeposits() {
        jdbc.update("DELETE FROM time_deposits");
    }

    @Test
    void inTransaction_commitsTheWork_andReturnsItsResult_whenTheWorkReturns() {
        String result = transactionRunner.inTransaction(() -> {
            insertDeposit(1);
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(depositIds()).containsExactly(1);
    }

    // Also proves the work runs in a transaction: with auto-commit the insert would survive.
    @Test
    void inTransaction_rollsBackTheWork_andRethrowsTheSameException_whenTheWorkThrows() {
        IllegalStateException failure = new IllegalStateException("simulated failure");

        assertThatThrownBy(() -> transactionRunner.inTransaction(() -> {
            insertDeposit(1);
            throw failure;
        })).isSameAs(failure);

        assertThat(depositIds()).isEmpty();
    }

    private void insertDeposit(int id) {
        jdbc.update("INSERT INTO time_deposits (id, plan_type, days, balance) VALUES (?, 'basic', 31, 1000.00)", id);
    }

    private List<Integer> depositIds() {
        return jdbc.queryForList("SELECT id FROM time_deposits ORDER BY id", Integer.class);
    }
}
