package org.ikigaidigital.adapter.out.persistence;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * Only the queries the adapter needs (no generic save/delete). Each query is ONE SQL statement, so
 * a GET response always comes from one snapshot (no read skew under READ COMMITTED) and there is
 * no N+1. Hibernate de-duplicates the root entities of the fetch join.
 */
interface TimeDepositJpaRepository extends Repository<TimeDepositEntity, Integer> {

    @Query("SELECT d FROM TimeDepositEntity d LEFT JOIN FETCH d.withdrawals w ORDER BY d.id, w.id")
    List<TimeDepositEntity> findAllWithWithdrawals();

    /**
     * The LIMIT is in the subquery, never on the fetch-join query itself: it counts deposits, not
     * joined rows, so Hibernate does not fall back to in-memory pagination. LIMIT in a subquery is
     * HQL (not standard JPQL); PostgreSQL supports {@code IN (SELECT ... LIMIT n)}.
     */
    @Query("""
            SELECT d FROM TimeDepositEntity d LEFT JOIN FETCH d.withdrawals w
            WHERE d.id IN (SELECT d2.id FROM TimeDepositEntity d2
                           WHERE d2.id > :afterId ORDER BY d2.id LIMIT :limit)
            ORDER BY d.id, w.id""")
    List<TimeDepositEntity> findPageWithWithdrawals(int afterId, int limit);
}
