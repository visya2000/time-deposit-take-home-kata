package org.ikigaidigital.application.port.in;

public interface UpdateAllBalancesUseCase {

    /** Applies the monthly interest to every deposit that is due; safe to call repeatedly. */
    void updateAllBalances();
}
