package org.ikigaidigital.adapter.out.persistence;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Only the queries the adapter needs (no generic save/delete). The GET queries are each ONE SQL
 * statement, so a response always comes from one snapshot (no read skew under READ COMMITTED) and
 * there is no N+1. Hibernate de-duplicates the root entities of the fetch join.
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

    /**
     * Native SQL for {@code FOR UPDATE SKIP LOCKED}. Selects all columns: Hibernate can only build
     * an entity from a row containing every mapped column. Withdrawals are not touched (lazy).
     */
    @Query(value = """
            SELECT td.* FROM time_deposits td
            WHERE td.id > :afterId
              AND (td.last_interest_applied_on IS NULL OR td.last_interest_applied_on <= :cutoff)
            ORDER BY td.id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<TimeDepositEntity> lockNextDue(LocalDate cutoff, int afterId, int limit);

    /** Served from the persistence context when the entity was loaded in the same transaction. */
    Optional<TimeDepositEntity> findById(Integer id);
}
