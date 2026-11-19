package org.ikigaidigital.domain;

import java.util.List;

/** One keyset page of deposits; {@code hasNext} is true when deposits with higher ids exist. */
public record TimeDepositPage(List<TimeDepositWithWithdrawals> items, boolean hasNext) {

    public TimeDepositPage {
        items = List.copyOf(items);
    }
}
