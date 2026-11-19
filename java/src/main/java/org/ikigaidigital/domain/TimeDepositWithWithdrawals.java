package org.ikigaidigital.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read model of the GET endpoint. It never passes through the interest calculation, so the balance
 * stays an exact {@link BigDecimal} from the database to the API.
 */
public record TimeDepositWithWithdrawals(
        int id, String planType, BigDecimal balance, int days, List<Withdrawal> withdrawals) {

    public TimeDepositWithWithdrawals {
        withdrawals = List.copyOf(withdrawals);
    }
}
