# Implementation Plan — Time Deposit Kata (Java)

Status: **in progress** — steps 1–14 implemented; steps 15 (shared kernel), 16 (back-fill enhancement) and 17 (DB rollback test) planned (see §8).

Approach: Requirements → code analysis → identify issues → tests → coverage review → refactoring →
architecture & dependency review → dead code removal → final code review → full test suite run.

---

## 1. Requirements analysis

### What exists vs. what is missing

| Area | State |
|---|---|
| Interest logic (`TimeDepositCalculator.updateBalance`) | Exists. Declared correct — **behaviour must stay identical**. |
| Tests | Effectively none — `TimeDepositCalculatorTest` only asserts `1 == 1`. |
| Withdrawals | Missing entirely (no domain type), but required in the DB and in the GET response. |
| REST API, database, OpenAPI contract | Missing (project has only JUnit + AssertJ, no Spring, no DB). |
| AI-assisted development documentation | Missing. |

### Requirements coverage

| README requirement | Where in this plan |
|---|---|
| Endpoint to update balances of all deposits | §3, §5 (`POST /time-deposits/balance-updates`) |
| Endpoint to get all deposits (`id, planType, balance, days, withdrawals`) | §3 GET, §5 |
| Tables `timeDeposits`, `withdrawals` | §4 (snake_case, justified) |
| Basic 1 %, Student 3 % (none after 1 year), Premium 5 % (after 45 days), 30-day grace | §1 behaviour, §6 characterization tests |
| No breaking change to `TimeDeposit`, `updateBalance` signature and behaviour kept | §1 constraints, §2, protect hook (§7) |
| Extensible interest design, SOLID | §2 strategy pattern, single registration point |
| Exactly two endpoints | §5 |
| Ambiguities resolved by justified assumptions (in code comments) | §1 Assumptions |
| AI harness, documented and reproducible | §7 |
| OpenAPI contract, hexagonal architecture, atomic commits, Testcontainers | §5, §2, §8, §6 |
| Swagger instructions for triggering the endpoints | §8 step 13 |

### Constraints

- `TimeDeposit` stays untouched: same package (`org.ikigaidigital`), constructor, getters, `Double balance`.
- `updateBalance(List<TimeDeposit>)` keeps its signature **and its observable behaviour**.
- `new TimeDepositCalculator()` (no-arg constructor) keeps working.
- Exactly **two** API endpoints.
- Database: **PostgreSQL** (required).
- Invalid-input / exception handling is out of scope (the only input rule is the declared range
  of the `limit` query parameter, enforced by generated validation — §3 GET).

### Current behaviour of `updateBalance` (locked in by characterization tests)

- No interest while `days <= 30` (interest starts on day 31).
- **Basic**: 1 % p.a. when `days > 30`.
- **Student**: 3 % p.a. only while `days < 366` → day 365 still earns interest, day 366 does not.
- **Premium**: 5 % p.a. only when `days > 45` → starts on day 46.
- Monthly interest = `balance * rate / 12`, computed in `double`; rounded with
  `new BigDecimal(double).setScale(2, HALF_UP)`; added back to the balance as `double`.
- Unknown plan type (incl. different case, e.g. `"Basic"`) → no interest, no exception.
  (`null` plan type: see decision 22.)
- Only `balance` is modified; `id`, `planType`, `days` are never touched. The update endpoint
  therefore persists only the **balance** plus the internal scheduling marker
  (`last_interest_applied_on`, §3) — `days` is left as is.

### Weaknesses in the existing code

- Plan rules are hard-coded in a nested if/else chain with magic strings → every new plan
  requires editing the method (violates Open/Closed).
- One loop mixes rule selection, interest calculation and balance mutation (violates SRP).
- Index-based loop with repeated `xs.get(i)` hurts readability.
- `double` for money — frozen by the "no breaking changes" constraint, so it is contained
  rather than propagated (converted to `BigDecimal` only at the persistence/API boundary).
- The only test asserts nothing.

### Assumptions (justified in code comments and listed in the project README)

1. `days` is the deposit's age in days, maintained by another system and current as of today.
2. The balance-update endpoint is triggered **daily** by a scheduler. Interest is **monthly per
   deposit**: each deposit is posted at most once per calendar month on its own cycle, so a
   second call in the same cycle changes nothing (idempotent by design — stated in the OpenAPI
   description of the POST).
3. Withdrawals do not affect interest (no such rule in the README); whether withdrawals change
   `balance` is not specified.
4. Unknown plan types earn no interest (existing behaviour, preserved).
5. DB names are snake_case; API names are camelCase as in the README (§4).
6. One extra column, `time_deposits.last_interest_applied_on`, is added beyond the README schema
   (§3); it is not exposed by the API.
7. Missed months are back-filled (planned, step 16): a run posts every month owed since the last posting (or since
   the deposit first became eligible), judging eligibility by the deposit's age on each due date
   (§3 "Backfilling", step 16).
8. Business dates are computed in a configured zone, default `UTC` (§3).
9. Withdrawals are returned as `{ id, amount, date }` (README does not define the inner shape).
10. Ids are generated by an identity column, so new deposits get increasing ids (§4).
11. Existing balances contain no interest yet: the first run back-fills every month since each
    deposit became eligible. If an earlier system already posted interest, the markers must be set
    by a data migration first (§3 "Backfilling").

---

## 2. Target architecture — Hexagonal (single Maven module)

```
org.ikigaidigital
├── TimeDeposit, TimeDepositCalculator          ← unchanged location (shared contract)
├── domain
│   ├── interest/   InterestStrategy, InterestStrategies (default set),
│   │               BasicInterestStrategy, StudentInterestStrategy, PremiumInterestStrategy
│   ├── Withdrawal                    (record)
│   ├── TimeDepositWithWithdrawals    (read model for GET)
│   └── TimeDepositPage               (read model: items + hasNext)
├── application
│   ├── port/in     UpdateAllBalancesUseCase, GetAllTimeDepositsUseCase
│   ├── port/out    TimeDepositRepository, TransactionRunner
│   └── TimeDepositService            (implements both use cases)
└── adapter
    ├── in/web            controller implementing the generated OpenAPI interface, DTO mapping,
    │                     Link header
    ├── out/persistence   TimeDepositEntity, WithdrawalEntity, Spring Data repository
    │                     (HQL for reads, native SQL for the locking update), mappers,
    │                     repository adapter, TransactionTemplate-based TransactionRunner
    └── config            beans: Clock, TimeDepositCalculator, TimeDepositService
```

- Domain is plain Java: no Spring/JPA annotations. The calculator is exposed as a bean via a
  `@Configuration` class in the adapter/config layer.
- Dependency direction: `adapter → application → domain`, never the reverse.
  Guarded by code review and the `architecture-reviewer` subagent (ArchUnit: §11).
- Per-chunk transactions: the application service owns the flow and the business rule
  ("mark only deposits whose balance changed") and calls a tiny `TransactionRunner` outbound
  port; the adapter implements it with Spring's `TransactionTemplate`. The application layer
  stays free of Spring (`@Transactional`) and is unit-testable with a fake.
- One `TimeDepositService` implements both use cases (decision 23). Considered: splitting into a
  query service (repository only) and a balance-update service (repository, transaction runner,
  calculator, clock, chunk size). Kept as one: two use cases over one aggregate, a single
  application entry point for a small codebase; a split remains cheap if either use case grows.

### Interest refactoring — Strategy pattern

```java
public interface InterestStrategy {
    String planType();
    double monthlyInterest(TimeDeposit deposit); // unrounded; same double expression as today
}
```

- Each plan implementation owns its full eligibility rule (30-day grace period + its own
  limit). The README says the grace period applies to "existing plans", implying a future plan
  may differ — so it is not hard-wired into the calculator. Adding a plan = adding one class.
- **Single registration point**: `InterestStrategies.defaults()` returns the default set. Both
  the no-arg `TimeDepositCalculator()` and the Spring configuration use it, so a new plan is
  registered in exactly one place.
- `TimeDepositCalculator`:
  - keeps the no-arg constructor (uses `InterestStrategies.defaults()`);
  - gains a constructor accepting `Collection<InterestStrategy>` (extension point / tests);
  - resolves the strategy from an immutable `Map<String, InterestStrategy>`; unknown plan →
    zero interest;
  - rounding and adding the interest stay in the calculator, identical in effect.
- **No BigDecimal arithmetic inside the calculation.** Switching would change results at
  rounding edges, and the README states the current behaviour is correct. The floating-point
  expressions (`balance * 0.03 / 12`, etc.) keep the same operand order.

### Money types: BigDecimal everywhere except the frozen calculation

Measured over every balance 0.01–100,000.00, exact BigDecimal maths differs from the current
`double` algorithm by 1 cent for 3,019 (basic), 17,069 (student) and 10,216 (premium) of 10M
balances. Example: basic, 18.00 → current code 18.01 (`18 * 0.01 / 12` = `0.01499999…` in
double); exact maths 18.02. So BigDecimal inside `updateBalance` is a behaviour change, not a
refactoring — and `TimeDeposit.balance` is `Double` in a frozen class.

- DB: `NUMERIC(19,2)`; API: `BigDecimal`; `Withdrawal.amount`: `BigDecimal` (new class).
- `double` lives only transiently inside one calculation. The persistence adapter converts with
  `BigDecimal.valueOf(d).setScale(2, HALF_UP)` (since step 16 the shared domain helper `Cents`,
  also used between back-filled postings), so every write re-quantises to cents and float
  error never accumulates across runs (e.g. `1000.10 + 0.83 = 1000.9300000000001` in double →
  stored `1000.93`); the DB is the source of truth.
- Precision limit (documented): `double` represents cents exactly only up to about 9×10¹³,
  while `NUMERIC(19,2)` allows larger values — a consequence of the frozen `Double` contract.
- Recorded as tech debt in an ADR: moving the calculation to exact decimal maths is a business
  decision (it changes customer balances) — contained to the strategies + calculator.

---

## 3. Balance update: idempotency, scalability, concurrency

### Model: per-deposit monthly cycle

Deposits start on different days, so interest is due per deposit, not per calendar run.
`time_deposits.last_interest_applied_on DATE NULL` records the **scheduled due date** of the last
interest posting (`NULL` = never posted). Since step 16 it is no longer the date of the run: a
late run back-fills every owed month and the marker keeps the deposit's own cycle (§3
"Backfilling missed months").

- `today` = `LocalDate.now(clock)`, where `clock` is a `Clock` bean in a configured business
  zone (`interest.business-zone`, default `UTC`) — never the server's default zone, so servers
  in different time zones agree. Computed **once per run**, so a run spanning midnight is
  consistent. Tests use `Clock.fixed`.
- **Due** ⇔ `last_interest_applied_on IS NULL OR last_interest_applied_on <= today.minusMonths(1)`.
  The cutoff is computed in Java and passed as a parameter (single source of the rule).
- **1 calendar month, not 30 days** (commented in code): interest per posting is `rate / 12`,
  so postings must be monthly. 30-day cycles would give ~12.17 postings/year and overpay ~1.4 %
  of annual interest. Simulated over every start date 2024–2027 with daily runs: at most
  12 postings in any 1-year window, gaps 28–31 days. Month-end anchors drift to the next valid
  day (Jan 31 → due Mar 1 → then the 1st), never two postings within a month.
- **Marker advances only when the balance changed** (interest ≠ 0), to the last due date
  evaluated. Deposits still in a grace period (≤ 30 days, premium ≤ 45) are re-checked every run
  and get their first interest as soon as they become eligible, then monthly from that date.
  Never-again-eligible deposits (student > 365 days, unknown plan, interest rounding to 0.00)
  are re-evaluated every run without effect — harmless, idempotent.
- The endpoint is meant to be triggered **daily** by a scheduler (documented). Called less
  often, or after an outage, it back-fills every owed month (step 16); it never double-applies.

### Run algorithm (application service, single-threaded)

```
today = LocalDate.now(clock); cutoff = today.minusMonths(1); lastId = 0
loop:
  lastId = tx.inTransaction(() -> {
      chunk = repo.lockNextDue(cutoff, afterId = lastId, size)    // SQL below; deposit + marker
      if chunk empty -> return DONE
      postings = for each deposit: back-fill all owed months       // §3 "Backfilling", step 16
      repo.saveAppliedInterest(postings)                           // changed deposits only:
                                                                   //   (id, new balance, new marker)
      return max id in chunk
  })
until DONE
```

Before step 16 the chunk was passed to `calculator.updateBalance(chunk)` once and every changed
deposit was marked with `today`; with daily runs the back-fill gives exactly the same result.

```sql
SELECT td.* FROM time_deposits td
WHERE td.id > :afterId
  AND (td.last_interest_applied_on IS NULL OR td.last_interest_applied_on <= :cutoff)
ORDER BY td.id
LIMIT :size
FOR UPDATE SKIP LOCKED;
```

- The **`id > :afterId` keyset cursor is essential**, not just a pagination detail: deposits
  that stay due (unchanged balance, marker not advanced) would otherwise be returned again by
  every chunk query → infinite loop. With the cursor, every row is evaluated at most once per
  run. An empty table simply yields an empty first chunk.
- Rows inserted during a run get higher ids (identity), so they are reached by the cursor in
  the same run or picked up by the next one.
- The locking query is a Spring Data **native query** returning managed `TimeDepositEntity`s.
  It selects **all** columns (`td.*`): Hibernate can only build an entity from a result row
  that contains every mapped column, including the marker.
- Writes: `saveAppliedInterest` sets `balance` and `last_interest_applied_on` on the entities
  already loaded in the same transaction (no extra SELECT), only for deposits whose balance
  changed; on commit Hibernate's dirty checking flushes **only those**, as batched `UPDATE`s
  (`hibernate.jdbc.batch_size=500`, `order_updates=true`). Each chunk is its own transaction, so
  the persistence context is cleared per chunk and never grows.
- Defaults: chunk size 500; Hikari default pool (10). The request thread holds no connection
  outside the chunk transactions (`open-in-view=false`).

### Correctness under failure and concurrency

| Scenario | Outcome |
|---|---|
| Endpoint called twice the same day / client retry after timeout | Deposits posted today are no longer due → no-op for them. |
| Crash or error mid-run | Committed chunks are marked; the failed chunk rolls back atomically (verified against PostgreSQL in step 17); the next run continues with the rest. No partial chunk, no double posting. |
| Two runs at once (same or different instances) | `FOR UPDATE SKIP LOCKED`: a row is processed by exactly one run; the other skips it. PostgreSQL re-checks the `WHERE` on the latest committed row version when locking, so a row another run just posted is no longer due. |
| Other writers on a row we hold (balance updates, withdrawal inserts) | They wait until our chunk commits (ms) — intended: interest is applied before a concurrent withdrawal. No lost update; `@Version` not needed. A withdrawal insert waits too, because its foreign-key check takes `FOR KEY SHARE` on the deposit row, which conflicts with `FOR UPDATE`. |
| Row locked by any other transaction when we reach it (another run, an in-flight withdrawal, …) | Skipped (`SKIP LOCKED`); still due → processed by the next run, which back-fills the posting for its original due date — the deposit's cycle does not shift. |
| Deadlocks | Our `SELECT` never waits (`SKIP LOCKED`), our `UPDATE`s touch only rows we already hold → we never wait on others. |
| Servers in different time zones / small clock skew | Same configured zone → same `today`. A double posting would need a skew of ~1 month. |
| Scheduler outage of N days | The next run back-fills every missed month (eligibility judged by the deposit's age on each due date); cycles keep their dates (step 16). |
| First deployment | All markers `NULL` → the first run back-fills every month since each deposit first became eligible. **Assumption: existing balances contain no interest yet**; if an earlier system already posted interest, set the markers by a data migration before the first run (§3 "Backfilling"). |

Alternative considered: `FOR NO KEY UPDATE SKIP LOCKED` would not block withdrawal inserts (FK
checks) and would not skip deposits with an in-flight withdrawal insert, while still excluding
concurrent runs and balance `UPDATE`s. Not chosen: having concurrent writers wait for the
interest posting is the intended ordering.

### Locking model: writes lock, reads never block

PostgreSQL MVCC: plain `SELECT`s take no row locks and are never blocked by `FOR UPDATE` or
`UPDATE`; they see the last committed row version.

| Operation | Locks | Blocked by an update run? |
|---|---|---|
| `GET` (plain `SELECT`) | none | Never — may return data a few ms stale (accepted: a refresh shows the update). |
| Update run | row locks, one short chunk transaction at a time | Never waits (`SKIP LOCKED`). |
| Other writer on a row we hold | waits | Only until our chunk commits (ms). |

### Backfilling missed months (step 16)

Goal: a run produces **exactly what daily runs would have produced** since the deposit's last
posting — or since it was opened, if it was never posted. With daily runs nothing changes (all
existing balance-update tests stay valid); after an outage, a skipped (locked) row, or on the
first deployment, every owed month is posted and each deposit keeps its own cycle.

**Due dates of a deposit** (domain `InterestSchedule`, pure date logic):

- `next(d)` = `d.plusMonths(1)` if that keeps the day of month, otherwise the 1st of the
  following month (Jan 31 → Mar 1 → Apr 1; Jan 15 → Feb 15; Jan 29 → Feb 29 in a leap year).
  This is exactly when the existing due rule (`marker <= today.minusMonths(1)`) makes a deposit
  due again, so the SQL filter stays unchanged and consistent. Plain `marker + n months` would
  not be: Jan 31 + 1 month = Feb 28 → due again Mar 28 → two postings in March (13 a year).
- **Marker set**: due dates are `next(marker)`, `next(next(marker))`, … up to and including
  `today`.
- **Marker `NULL`** (never posted): daily runs would have posted the first interest on the
  first day the deposit was eligible. The deposit was opened `days` days before `today`; the
  first due date is the first day after opening on which the frozen calculator returns non-zero
  interest for the deposit's age on that day (basic/student: day 31, premium: day 46 — found
  generically by asking the calculator, no plan rule duplicated). Then `next(…)` as above. No
  eligible day yet (grace period, unknown plan) → no posting, marker stays `NULL`.

**Posting each due date** (application service, frozen calculator unchanged):

```
balance = deposit.balance
for each due date d:
    copy = new TimeDeposit(id, planType, balance, days − DAYS.between(d, today))  // age on d
    calculator.updateBalance(List.of(copy))                                        // frozen
    balance = Cents.round(copy.getBalance())        // as an on-time run stores it (see below)
balance changed → posting(id, balance, marker = last due date)
```

- **Eligibility on each due date**, not today: a student deposit now 380 days old that missed a
  month at day 350 gets it; a premium deposit now 50 days old that missed a month at day 20 does
  not. On the day a deposit becomes due, its age then equals `days`, so daily runs are unchanged.
- **Compounding like real runs**: each month's interest is computed on the balance after the
  previous posting, **re-rounded to cents** in between, because on-time runs store (and re-read)
  the balance in cents. Without it, back-filled premium balances differ by a cent in 6,091 of
  10M cases after two postings (e.g. 3.59 → 3.61 instead of 3.62). The rounding rule
  (`BigDecimal.valueOf(d).setScale(2, HALF_UP)`) moves from the persistence adapter into one
  domain helper (`Cents`) used by both — one rule.
- **No cap** on the number of months per run: the interest is owed either way; a cap would
  silently drop money (a corrupt marker would show up as a large posting instead).
- **Marker** = the last due date evaluated (≤ `today`, and `next(marker) > today`), so the
  deposit is not due again until its next cycle. Marker advanced only if the balance changed
  (unchanged rule). Idempotency, locking and per-chunk transactions are unchanged: all postings
  of a deposit happen in its chunk's transaction.

**Ports**: `lockNextDue` returns `DueTimeDeposit(TimeDeposit deposit, LocalDate
lastInterestAppliedOn)` (domain record; the frozen class has no marker);
`saveAppliedInterest(List<InterestPosting>)` with `InterestPosting(int depositId, double balance,
LocalDate lastInterestAppliedOn)` — the marker now differs per deposit.

**First deployment / initial run**: every marker is `NULL`, so the first run back-fills every
month since each deposit became eligible. **Assumption: today's balances contain no interest
yet.** If an earlier system already posted interest, set `last_interest_applied_on` by a data
migration before the first run, otherwise interest would be paid twice.

**Still not covered** (documented limitations):

- **Balance history**: withdrawals between missed due dates are not known per date; the
  current balance is used (consistent with assumption 3: withdrawals do not affect interest).
- **`days` must be current** (assumption 1): the age on past dates is derived from it.

Cost: a deposit with a marker evaluates one calculator call per owed month (one on daily runs).
A never-posted deposit additionally scans its days from opening to the first eligible day (≤ 46
calls for existing plans); a never-eligible one (unknown plan) scans its whole age on every run
(e.g. 400 cheap in-memory calls) — acceptable, noted as a limitation.

### Cost

- Each run walks the primary key once (`O(n)` index reads), locks and evaluates the due rows
  chunk by chunk and writes only changed rows. Memory is `O(chunk size)`; each chunk is a short
  transaction (no long-held locks, no growing persistence context).
- Rough estimate (not measured): 6–15k rows/s → 10M deposits in ~10–30 min for a daily job.
  If that is not enough: parallel workers (§11), measured first.
- No index on `last_interest_applied_on`: with keyset-by-id ordering PostgreSQL would not use it.
- Only `balance` and the marker change and neither is indexed, so PostgreSQL *can* use HOT
  (in-place) updates when the page has free space; a lower `fillfactor` is an option only if
  measurements show a need.
- Synchronous endpoint: at very large sizes the HTTP call can time out. That is safe (the server
  finishes; a retry is a no-op or shares the work). Options: §11.

### GET endpoint

**Optional keyset pagination** (still the one GET endpoint):

- No parameters → **all** deposits (README behaviour, backward-compatible).
- `limit` (1–1000) + optional `afterId` (default: start) → one page, ordered by `id`. If only
  `afterId` is given, `limit` defaults to 100. The range is declared in the OpenAPI contract and
  enforced by the generator's bean validation (`useBeanValidation` +
  `useSpringBuiltInValidation`: no class-level `@Validated`, so Spring MVC's built-in method
  validation answers with an automatic `400`; the AOP variant would end in `500`) — no
  hand-written error handling. Without it, `limit=0` would yield an empty page with a `next`
  link pointing nowhere.
- Response body is the **same array** in both modes (no second response shape). When a next
  page exists: `Link: </time-deposits?afterId=<last id>&limit=<n>>; rel="next"` (RFC 8288),
  declared in the contract. Path-absolute on purpose: an absolute URL built from the request
  would take scheme/host from headers (wrong behind a reverse proxy, Host-header injection) and
  re-encode other query parameters; a query-only reference (`<?afterId=..>`) is mis-resolved by
  some clients (e.g. `java.net.URI.resolve` drops the path). No total
  count (`COUNT(*)` is expensive on large tables).
- Keyset instead of offset (`page`/`size`): constant cost per page regardless of depth (PK
  index), and stable while data changes between requests — ordered by immutable, increasing
  `id`, so no duplicates or skipped rows. Trade-off: no "jump to page N".

**HQL (Hibernate 7 JPQL superset), one SQL statement per request:**

```java
// paginated — called with limit + 1
@Query("""
    SELECT d FROM TimeDepositEntity d LEFT JOIN FETCH d.withdrawals w
    WHERE d.id IN (SELECT d2.id FROM TimeDepositEntity d2
                   WHERE d2.id > :afterId ORDER BY d2.id LIMIT :limit)
    ORDER BY d.id, w.id""")
List<TimeDepositEntity> findPageWithWithdrawals(int afterId, int limit);

// unpaginated
@Query("SELECT d FROM TimeDepositEntity d LEFT JOIN FETCH d.withdrawals w ORDER BY d.id, w.id")
List<TimeDepositEntity> findAllWithWithdrawals();
```

- The `LIMIT` is inside the subquery, never on the fetch-join query itself (no `setMaxResults`),
  so Hibernate does not paginate in memory, and the limit counts **deposits**, not joined rows.
- `ORDER BY d.id, w.id` makes the withdrawal order within a deposit deterministic (stable API
  output and tests).
- Paginated: fetch `limit + 1` deposits; the extra one only signals a next page (→ `Link`
  header) and is dropped. The use case returns a `TimeDepositPage(items, hasNext)`; the web
  adapter builds the `Link` header from it.
- Hibernate de-duplicates root entities from the join (no manual grouping); a deposit without
  withdrawals gets an empty collection → `withdrawals: []`. Entities are mapped to the domain
  read model inside the adapter's `@Transactional(readOnly = true)` method (no dirty-checking
  snapshots).
- `LIMIT` in a subquery is **Hibernate-specific HQL, not standard JPQL**; PostgreSQL supports
  `IN (SELECT … LIMIT n)` (some databases, e.g. MySQL, do not) — acceptable, PostgreSQL is
  required.
- **Verify first (step 9):** a Testcontainers spike that Spring Data's `@Query` parser accepts
  the subquery `LIMIT` and that it runs as one statement. Fallbacks, in order: the same HQL via
  `EntityManager.createQuery` in a custom repository fragment (bypasses Spring Data's parser);
  native SQL (deposits limited in a derived table, `LEFT JOIN` withdrawals) with manual row
  grouping. **Result (step 9):** Spring Data JPA 4.1 / Hibernate 7.4 accept the HQL in `@Query`
  as is; one SQL statement per call is verified by `TimeDepositPersistenceAdapterReadIT` —
  no fallback needed.
- Indexes: PK serves the subquery's `ORDER BY id LIMIT`; `withdrawals.time_deposit_id` serves
  the join.

**Consistency (read skew) — why no `REPEATABLE READ`:** under PostgreSQL's default
`READ COMMITTED`, each *statement* gets its own snapshot ("a SELECT sees only data committed
before the query began"). Two statements (e.g. deposits, then withdrawals) could mix two points
in time. The GET is **one** statement in both modes, so a response always comes from one
snapshot. (The `READ COMMITTED` re-check of newer row versions applies only to `UPDATE` /
`SELECT … FOR UPDATE`, not to a plain `SELECT`.) The query takes no locks. If the GET ever has
to become two statements, wrap them in a `REPEATABLE READ` read-only transaction.

Guard: a test asserts the GET executes exactly one SQL statement in both modes (Hibernate
statistics, `prepareStatementCount == 1`) — protects the snapshot guarantee and prevents N+1.

**Accepted staleness:** a GET during an update run may show some deposits already updated and
others not (the run commits per chunk). Across pages, each page is its own snapshot (page 1
before a run, page 2 after) — keyset still guarantees no duplicates or skipped rows.

The unpaginated mode still loads everything into memory; pagination is the scalable path and
is recommended for large datasets (documented in the README).

**Entities:**
- `TimeDepositEntity` — loaded and dirty-flushed by the update path; has a unidirectional
  `@OneToMany(fetch = LAZY) @JoinColumn(name = "time_deposit_id") List<WithdrawalEntity>`
  used only by the GET fetch join (the update's native query never touches it). `@Id` without
  `@GeneratedValue` (the application never inserts deposits).
- `WithdrawalEntity` — read-only use, **no back-reference** to the deposit (a default-EAGER
  `@ManyToOne` could trigger extra statements and break the single-statement guarantee).
- With `open-in-view=false`, an accidental lazy load outside the adapter's transaction throws
  instead of silently issuing another statement.

### Other

- Schema owned by **Flyway** migrations (`db/migration`). Demo seed data lives in a separate
  location (`db/seed`) enabled only by the `local` profile, so integration tests start from an
  empty schema and control their own data. The seed covers every plan and the main boundaries
  (e.g. 30/31 days, premium 45/46, student 365/366, an unknown plan, deposits with and without
  withdrawals) for manual testing via Swagger.
- Manual re-run of interest for demos (documented in the README):
  `UPDATE time_deposits SET last_interest_applied_on = NULL;`
- Considered and rejected: an `interest_postings` ledger per calendar month (puts every deposit
  on the same calendar cycle and grows by deposits × 12 rows/year; its only extra value, an
  audit trail, is not required — §11), and an `Idempotency-Key` header (covers client retries
  only, not concurrent runs or partial failures).

---

## 4. Database schema

| Table | Column | Type | Constraints |
|---|---|---|---|
| `time_deposits` | `id` | `INTEGER` | PK, `GENERATED BY DEFAULT AS IDENTITY` |
| | `plan_type` | `VARCHAR` | NOT NULL |
| | `days` | `INTEGER` | NOT NULL |
| | `balance` | `NUMERIC(19,2)` | NOT NULL |
| | `last_interest_applied_on` | `DATE` | NULL — **addition** beyond the README schema (§3); not exposed by the API |
| `withdrawals` | `id` | `INTEGER` | PK, `GENERATED BY DEFAULT AS IDENTITY` |
| | `time_deposit_id` | `INTEGER` | NOT NULL, FK → `time_deposits.id`, indexed |
| | `amount` | `NUMERIC(19,2)` | NOT NULL |
| | `date` | `DATE` | NOT NULL |

- Identity ids: inserts by other systems get increasing ids without coordination — the keyset
  cursors (update run, pagination) rely on that. `BY DEFAULT` still allows explicit ids in seeds
  and tests.
- PostgreSQL does not index foreign keys automatically → explicit index on
  `withdrawals.time_deposit_id`.

### Naming: why not literally `timeDeposits` / `planType`?

PostgreSQL folds **unquoted** identifiers to lower case. `CREATE TABLE timeDeposits (planType ...)`
actually creates `timedeposits` with column `plantype` — the camelCase is silently lost.
Keeping the exact spelling requires double quotes (`"timeDeposits"`, `"planType"`) in every
migration and query, which is error-prone and non-idiomatic.

**Decision: snake_case** (`time_deposits`, `plan_type`, `time_deposit_id`).

- It is the PostgreSQL convention — no quoting anywhere in migrations or SQL.
- Spring Boot's default Hibernate naming strategy maps entity fields `planType` → `plan_type`
  automatically, so entities need no explicit `@Column` names.
- The API keeps the README's camelCase field names (`planType`, …); only the physical DB
  names differ.
- The deviation from the README is justified in a comment in the Flyway migration and listed
  in the project README's assumptions section.

Alternatives considered and rejected: quoted camelCase (spec-exact, but quoting everywhere is
noisy and error-prone in native SQL); unquoted camelCase (silently folded to `timedeposits` —
neither spec-exact nor readable).

`date` is a non-reserved keyword in PostgreSQL, so it works as a column name unquoted.

---

## 5. Stack and API

- **Java 17** — keep the existing `pom.xml` and its Java level (`maven.compiler.release=17`, so a
  newer local JDK cannot leak newer APIs). Single Maven module: the existing pom is extended.
- Spring Boot — latest stable release supporting Java 17: **4.1.2** (manages JUnit, AssertJ,
  Surefire/Failsafe, Hibernate 7.4, Flyway, Testcontainers). Mockito is excluded from
  `spring-boot-starter-test` — tests use in-memory fakes, enforced by the build.
- **Maven Wrapper** (`mvnw`) — reproducible builds for reviewers, CI and hooks without a local
  Maven install.
- Build plugins: Surefire (upgraded; `*Test`), Failsafe (`*IT`), JaCoCo (coverage report as a
  guide only, no threshold gate).
- PostgreSQL + **Spring Data JPA** + Flyway. JPA keeps entities/repositories available for future
  growth; where control matters (the `FOR UPDATE SKIP LOCKED` keyset query) a **native query**
  is used. Plain JDBC remains possible on the same connection/transaction if ever needed.
  Settings:
  - `spring.jpa.open-in-view=false` — the default would hold a DB connection for the whole HTTP
    request;
  - `spring.jpa.hibernate.ddl-auto=validate` — Flyway owns the schema;
  - `hibernate.jdbc.batch_size=500`, `hibernate.order_updates=true` — batched dirty-flush updates.
- **Contract-first OpenAPI**: `src/main/resources/static/openapi/time-deposits.yaml` →
  `openapi-generator-maven-plugin` 7.27 (`interfaceOnly`, `useSpringBoot4`, `useBeanValidation`,
  `useSpringBuiltInValidation`, no swagger annotations) → controller implements the interface
  (compile-time guarantee that the code matches the contract).
  - Swagger UI is a static page (`static/swagger-ui.html`, swagger-ui webjar +
    webjars-locator-lite) showing **this contract file**, so the contract stays the single
    source of truth. springdoc was dropped: in 3.2 its UI cannot run without also generating a
    second spec (`/v3/api-docs`).
  - `planType` is a plain `string`, not an enum — unknown plans exist (zero interest) and new
    plans must not require a contract change.
  - The POST description states the monthly-idempotency semantics (Assumption 2).
- Testcontainers (PostgreSQL); `docker-compose.yml` for running PostgreSQL locally.
- No Spring Boot Actuator (it would add endpoints). The Swagger UI page and the contract file are
  static documentation the README explicitly asks for, not business endpoints.

### Endpoints

| Method | Path | Response |
|---|---|---|
| `GET` | `/time-deposits` (optional `limit` 1–1000, `afterId`) | `200` — `[{ id, planType, balance, days, withdrawals: [{ id, amount, date }] }]`; `Link: …; rel="next"` header when paginated and more data exists; `400` for out-of-range `limit` (generated validation) |
| `POST` | `/time-deposits/balance-updates` | `204 No Content` (also when nothing was due) |

`POST`: the call triggers a process that changes state; it is safe to repeat (§3) but is
not a "replace this resource" operation, so `PUT` would be misleading.

---

## 6. Test strategy (tests before refactoring)

**Naming convention** (fast and slow suites run separately):
- `*Test.java` — unit tests, no Docker → `./mvnw test` (Surefire).
- `*IT.java` — Testcontainers / Spring context tests → `./mvnw verify` (Failsafe).

1. **Characterization tests** for `TimeDepositCalculator` (parameterized), run against the
   *current* implementation first, replacing the no-op test:
   - grace boundary 30 / 31 days for **each** plan (basic, student, premium);
   - premium inside its own window (e.g. 40 days → no interest) and boundary 45 / 46;
   - student boundary 365 / 366;
   - unknown plan type → balance unchanged; different case (`"Basic"`) → treated as unknown;
   - zero balance → stays zero;
   - the original test's scenario as a real assertion: basic, 1,234,567.00, 45 days →
     **1,235,595.81**;
   - a HALF_UP boundary where `double` differs from exact decimal maths:
     basic 18.00 → **18.01** (exact maths would give 18.02); plus one rounding edge per plan that
     also catches a reordered expression (`balance * (rate / 12)`, `balance / 12 * rate`):
     basic 30.00 → **30.02**, student 10.00 → **10.02**, premium 162.00 → **162.67**;
   - basic and premium have no upper day limit (400 days still earns interest);
   - empty list; mixed list — each deposit updated independently;
   - non-balance fields untouched.
2. **Strategy unit tests** — only each plan's own boundaries; no duplication of (1).
   Calculator with a custom strategy collection: a new plan is applied without touching
   existing strategies (extension point).
3. **Application service tests** with in-memory fakes (no mocking frameworks) and `Clock.fixed` —
   assert outcomes:
   - all due deposits updated across multiple chunks (chunk size smaller than the data set);
   - run terminates when many deposits stay due (ineligible) — guards the infinite-loop trap;
   - empty repository; single deposit;
   - same-day second run is a no-op;
   - due again exactly one calendar month later, not before (Jan 31 → not due Feb 28, due
     Mar 1); leap year (Jan 31 2028 → not due Feb 29, due Mar 1);
   - marker advanced only for deposits whose balance changed; a deposit in grace gets interest
     on the first run after becoming eligible;
   - failure in a middle chunk: earlier chunks stay applied, the failed chunk is unchanged, a
     re-run completes the rest without double posting;
   - the business date comes from the clock's zone: `Clock.fixed(2026-11-30T23:30Z, Asia/Tokyo)`
     → marker `2026-12-01` (catches `LocalDate.now()` without the clock);
   - GET use case: `hasNext` / items passed through for paginated and unpaginated calls;
   - **back-fill (step 16)** — all earlier balance-update tests stay valid unchanged (daily runs
     give the same result); in addition:
     - `InterestSchedule.next`: same day next month; month-end → 1st of the following month
       (Jan 31 → Mar 1; Mar 31 → May 1); leap year (Jan 29 2028 → Feb 29); due dates up to and
       including today, none when the next is after today;
     - marker set, 3 missed months → 3 postings, compounded and re-rounded to cents between
       postings (a premium rounding edge such as 3.59 → 3.62 after two months), marker = last
       due date (not the run date);
     - month-end outage: marker Jan 31, run Apr 15 → postings for Mar 1 and Apr 1, marker Apr 1;
     - eligibility on each due date: student now 380 days with a missed month at day ≤ 365 gets
       it, not the later ones; premium now 50 days with a missed month at day 20 does not;
     - a row skipped one day (locked) keeps its cycle: marker = original due date;
     - never posted (marker `NULL`): basic 31 days → one posting today (as before); basic 92
       days → postings at day 31 and each month after; premium first posting at day 46;
       never-eligible (unknown plan, grace period) → nothing, marker stays `NULL`;
     - no cap: a marker many months back posts every month.
4. **Persistence adapter tests** — Testcontainers PostgreSQL:
   - mapping and decimal scale; write rounding (`1000.10 + 0.83` → stored `1000.93`);
   - due filter + keyset cursor;
   - update writes balance and marker only for changed deposits (unchanged rows keep marker
     `NULL` and are not updated);
   - GET: withdrawals grouped per deposit, ordered by id; deposit without withdrawals → empty
     list; empty table → empty list;
   - GET executes **exactly one SQL statement** in both modes (Hibernate statistics enabled in
     the test profile);
   - pagination: a page holds exactly `limit` deposits even when they have many withdrawals;
     next-page detection at the exact boundary (rows = limit, limit + 1); last page; `afterId`
     beyond max id → empty; walking all pages yields every deposit exactly once.
5. **Concurrency tests** — Testcontainers:
   - two runs in parallel over a few thousand deposits; every eligible deposit receives
     interest exactly once;
   - reads are not blocked: while another transaction holds the update run's row locks, GET
     returns immediately with the last committed balances;
   - a row locked by another transaction is skipped and gets its interest on the next run;
   - an external balance update on a row in a chunk waits for the chunk to commit and is applied
     on top of the new balance (no lost update);
   - **mid-chunk rollback in the database (step 17)**: the service-level failure test uses the
     in-memory fake and fails before any write; this one proves a real PostgreSQL rollback of an
     UPDATE already executed inside the chunk. Real wiring, no test doubles: chunk size 2,
     deposits 1–6 eligible (chunks {1,2}, {3,4}, {5,6}); a test-created trigger raises an error
     on the UPDATE of deposit 4, so at the chunk's flush deposit 3's UPDATE runs and deposit 4's
     fails (`order_updates`). Asserted in the database: the run fails; 1 and 2 keep interest and
     marker (committed chunk); 3 is unchanged (its executed UPDATE rolled back); 4–6 unchanged.
     After dropping the trigger a re-run posts every deposit exactly once (no double posting).
6. **End-to-end tests** — `@SpringBootTest` + Testcontainers, over HTTP:
   - GET returns the README schema; empty DB → `[]`;
   - POST → `204`, GET shows updated balances; second POST → balances unchanged;
   - pagination: `Link` header present when more data exists, absent on the last page;
     `afterId` alone → 100 deposits; `limit=0` and `limit=1001` → `400`.

---

## 7. AI harness (README requirement 6)

Principle: **instructions are advice; hooks, tests and CI are enforcement.** Anything that must
never break is guarded deterministically, not only described in a prompt. The whole harness is
committed to the repo, so cloning it and running `claude` from the repo root reproduces it.

### Structure

```
CLAUDE.md                          project memory: constraints, architecture, commands, workflow
.gitattributes                     forces LF for *.sh (hooks run in Git Bash on Windows)
.claude/
├── settings.json                  shared permissions + hooks (committed)
├── settings.local.json            personal overrides (gitignored)
├── hooks/
│   ├── protect-contract.sh        PreToolUse  — blocks edits to TimeDeposit.java
│   ├── compile.sh                 PostToolUse — compiles after a .java edit
│   └── run-unit-tests.sh          Stop        — unit tests must be green before the turn ends
├── agents/
│   ├── test-writer.md             writes behaviour/boundary tests, never touches main code
│   └── architecture-reviewer.md   read-only review: hexagonal rules, SOLID, dead code, over-engineering
└── skills/
    └── add-plan-type/SKILL.md     playbook for adding a new interest plan (the extension point)
docs/
├── AI_USAGE.md                    tools, setup, config, which parts were AI-assisted and why
└── adr/                           short decision records (written in step 13 from §9)
.github/workflows/ci.yml           ./mvnw verify on push/PR (added with the Maven Wrapper)
```

### Components

| Component | Role |
|---|---|
| `CLAUDE.md` | Loaded into every session: frozen contract, architecture map, commands, workflow rules. |
| Permissions | Allow safe commands (`./mvnw`, read-only git, docker compose); `git commit` requires confirmation; `git push` denied. |
| `PreToolUse` hook | Rejects Edit/Write on `TimeDeposit.java` (exit 2 + reason fed back to Claude). |
| `PostToolUse` hook | Compiles after each `.java` edit so errors surface immediately. |
| `Stop` hook | Runs `./mvnw test` when `java/` has uncommitted changes; red tests block the turn from ending. Guarded by `stop_hook_active` against loops. Testcontainers tests are left to `verify`/CI to keep it fast. |
| Subagents | Fresh context, restricted role, return one report. `test-writer` (tests first), `architecture-reviewer` (diff review, every step). Built-in `/code-review` (correctness) in the final pass. |
| `add-plan-type` skill | Repeatable procedure: boundary tests → strategy class → registration in `InterestStrategies.defaults()` → tests → review. |
| CI | Same `./mvnw verify` on GitHub Actions (Docker available for Testcontainers). |

Hooks no-op until `java/mvnw` exists, so the harness can be installed before the build is.

### Working loop per plan step

1. Claude proposes the step in chat; the developer agrees or changes it.
2. Skeletons where production code is involved; `test-writer` adds tests that fail for the right
   reason.
3. Implement — hooks give compile feedback and block contract edits.
4. Stop hook enforces green unit tests; `./mvnw verify` before hand-over.
5. `architecture-reviewer` on the diff; fix findings or accept them with a reason.
6. Human reads every line; Claude commits on explicit approval.

The built-in `/code-review` (correctness) runs once over the whole change in the final pass
(step 14) rather than per step.

Maintenance: new rules → `CLAUDE.md` or a hook; design decisions → ADR; recurring tasks → skill.

### Deliberately not included

MCP servers, multi-agent workflows, more than two custom agents, formatter tooling — no clear
benefit at this size.

---

## 8. Implementation steps (each = one atomic commit, tests green after each)

1. AI harness: `CLAUDE.md`, `.claude/` (settings, hooks, agents, skill), `docs/AI_USAGE.md`.
2. Maven Wrapper; upgrade Surefire, add Failsafe + JaCoCo; update test dependencies; CI workflow.
3. Characterization tests for `TimeDepositCalculator`.
4. Extract `InterestStrategy` + plan strategies + `InterestStrategies.defaults()`; calculator
   delegates (characterization tests unchanged & green); strategy unit tests.
5. Spring Boot skeleton: parent, application class, configuration (`Clock`, calculator bean).
   Drop the local JUnit BOM, AssertJ and Surefire/Failsafe/compiler versions the Boot parent
   manages (keep JaCoCo), so Boot's dependency and plugin management applies.
6. Domain read models, ports, GET use case in `TimeDepositService` + tests. (The service is
   registered as a bean in step 10, once `TimeDepositRepository` and `TransactionRunner`
   implementations exist.)
7. Balance-update use case (`TransactionRunner` port, chunked keyset loop) + tests.
8. Flyway schema migration + `local`-profile seed + Testcontainers test setup.
9. Persistence read adapter: HQL spike (fallbacks if needed), GET queries + tests.
10. Persistence update adapter: locking native query, `saveAppliedInterest`, `TransactionRunner`
    implementation + tests. The IT must pin the SQL due filter: marker `NULL` and `= cutoff` are
    due, `cutoff + 1 day` is not (the service tests only prove the cutoff is computed correctly).
    `interest.update.chunk-size` (default 500) must be at least 1 — checked by the service
    constructor, so startup fails (configuration, not request input: `0` would be a silent no-op).
11. Concurrency tests.
12. OpenAPI contract, web adapter (`Link` header, validation), Swagger UI on the contract,
    end-to-end tests.
13. Documentation: README (run instructions, Swagger usage, assumptions, known limitations,
    possible enhancements, demo reset), ADRs; finalize `docs/AI_USAGE.md`.
14. Final pass: dead code removal, coverage review, full code review, full test suite run.
15. **Shared kernel documented** (decision 25): `TimeDeposit` and `TimeDepositCalculator` stay in
    `org.ikigaidigital` (moving them changes their fully qualified names — a breaking change);
    `package-info.java` and the calculator's Javadoc record it and suggest moving `TimeDeposit` into
    `domain` and the calculator into `domain.interest` in a coordinated breaking release; ADR 0006.
    `TimeDeposit.java` itself is not touched (frozen; the hook blocks edits).
16. **Enhancement — back-fill missed months** (§3 "Backfilling"): `InterestSchedule` and `Cents`
    in the domain (rounding moved from the persistence adapter), `DueTimeDeposit` /
    `InterestPosting` port records, back-fill loop in `TimeDepositService`, adapter returns the
    marker and writes per-deposit markers; unit tests (schedule, back-fill scenarios), adapter
    IT for the new port shape, end-to-end catch-up IT; README (assumptions, limitations, demo
    numbers), ADR 0007, `docs/AI_USAGE.md`. Expected changed expectations: tests/docs whose
    never-posted deposits are older than their first eligible day by more than a month (e.g. the
    365-day student deposit in `BalanceUpdateIT` and the README demo numbers).
17. **Mid-chunk rollback verified in the database** (§6 item 5): `BalanceUpdateRollbackIT` — a
    trigger fails the UPDATE of one deposit at the chunk's flush; the already-executed UPDATE of
    the previous deposit in the same chunk is rolled back, earlier chunks stay committed, and a
    re-run completes without double posting. Test only, no production change expected.

---

## 9. Decisions

| # | Topic | Decision |
|---|---|---|
| 1 | Table/column naming | snake_case in the DB (`time_deposits`, `plan_type`, …); camelCase in the API (§4). |
| 2 | POST response | `204 No Content`. |
| 3 | Fields persisted by the update | `balance` + internal marker; `days` untouched — `updateBalance` behaviour preserved exactly. |
| 4 | Database | PostgreSQL. |
| 5 | AI harness | Claude Code; committed config (§7); set up first so it is used throughout. |
| 6 | Money types | BigDecimal in DB/API/new classes; `double` only inside the frozen calculation (§2). |
| 7 | Java / modules | Java 17 (existing pom.xml); single Maven module. |
| 8 | Idempotency of balance update | `last_interest_applied_on` marker; due after 1 calendar month; advanced only when interest was applied; keyset cursor (§3). |
| 9 | ArchUnit | Dropped for now (over-engineering at this size); possible enhancement (§11). |
| 10 | Business time zone | `Clock` bean in `interest.business-zone`, default `UTC`. |
| 11 | Persistence technology | Spring Data JPA; native SQL for the locking update query; `open-in-view=false` (§5). |
| 12 | Locking | `FOR UPDATE SKIP LOCKED` per chunk; concurrent writers (incl. withdrawal inserts) wait until the chunk commits — intended; rows locked by others are processed next run; reads never block (MVCC). |
| 13 | Backfilling missed months | Planned for step 16 (§3): every owed month posted as daily runs would have done; next due date = same day next month or the 1st of the following month; eligibility by the age on each due date; never-posted deposits back-filled from their first eligible day; marker = last due date; no cap. Assumes existing balances contain no interest yet. |
| 14 | Parallel batch updates | Not implemented; single-threaded chunked run. Documented as a possible enhancement (§11). |
| 15 | GET query | HQL fetch join ordered by deposit and withdrawal id; paginated variant limits deposit ids in a subquery (`IN (SELECT … LIMIT)`) → one SQL statement → one snapshot, no read skew; enforced by a test. Spike first; fallbacks: `EntityManager` HQL, then native SQL. |
| 16 | PgBouncer | Not needed (instances × pool ≪ `max_connections`); possible enhancement (§11). |
| 17 | GET pagination | Keyset, optional: `limit` (1–1000, generated validation → 400) + `afterId`; no params → all; same array body; `Link rel="next"` header; no total count. |
| 18 | Strategy registration | Single source `InterestStrategies.defaults()`, used by the no-arg calculator and Spring config. |
| 19 | Seed data | Separate Flyway location, `local` profile only; tests start from an empty schema. |
| 20 | Ids | `GENERATED BY DEFAULT AS IDENTITY` (increasing ids for keyset stability). |
| 21 | API tooling | Static Swagger UI page (webjar) shows the contract file; no springdoc, no `/v3/api-docs`, no Actuator; `planType` is a free string in the contract. |
| 22 | `null` plan type | Treated as unknown (no interest, no exception). Previously a `NullPointerException` when `days > 30`; accepted as invalid input (out of scope), documented in `TimeDepositCalculator`. |
| 23 | Application service | One `TimeDepositService` implements both use cases; split considered and deferred (§2). |
| 24 | Cents rounding | One domain helper (`Cents`, `BigDecimal.valueOf(d).setScale(2, HALF_UP)`) used by the persistence adapter on write and by the back-fill between postings. |
| 25 | Shared kernel | `TimeDeposit` and `TimeDepositCalculator` stay in `org.ikigaidigital` (moving them = breaking change); documented as a shared kernel with a suggested coordinated move (`TimeDeposit` → `domain`, calculator → `domain.interest`; ADR 0006). Strategies keep the full `TimeDeposit` as input; Spring main class stays in the root package. |

---

## 10. Prerequisites

- Docker Desktop running (required by Testcontainers and docker-compose).
- Work in a personal fork: ✅ `origin` = `visya2000/time-deposit-take-home-kata` (public fork);
  `upstream` = `ikigai-digital/time-deposit-take-home-kata` (fetch only, push disabled).
- Claude Code installed; Git Bash available (used to run hooks on Windows).

---

## 11. Possible enhancements (not planned)

- **Parallel batch updates** — the job is bound by DB round trips and commits, not app CPU, so
  K workers would give a constant-factor speed-up until the DB saturates. Design: split
  `[min(id), max(id)]` into K disjoint ranges (cursor `id > :afterId AND id <= :rangeEnd`),
  one worker per range on a **shared fixed-size executor** (caps update connections at K even
  with concurrent POSTs; Java 17 → platform threads), `interest.update.parallelism` validated
  `< pool size` at startup so GET requests are not starved. Correctness is unchanged (marker +
  `SKIP LOCKED`). Measure throughput first (single worker vs. K), noting that laptop
  measurements are not representative of production.
- **Asynchronous update** — `202 Accepted` + background processing removes the HTTP-timeout
  risk; needs no extra endpoint, since clients observe results via GET.
- **Days-range filtering/paging** — order by `(days, id)` with a composite index `(days, id)`;
  keyset condition `(days, id) > (:afterDays, :afterId)` plus
  `days BETWEEN :minDays AND :maxDays` → index range scan, constant cost per page (filtering by
  days while ordering by `id` is not constant cost). In the HQL subquery:
  `(d2.days > :afterDays OR (d2.days = :afterDays AND d2.id > :afterId)) ORDER BY d2.days, d2.id`,
  outer `ORDER BY d.days, d.id`; check with `EXPLAIN`. Two-value cursor → opaque `cursor`
  parameter (clients following the `Link` URL are unaffected). Caveat: `days` is mutable — a row
  whose `days` grows between requests can appear twice; mitigate by client de-duplication by
  `id`, or long term store an immutable `opened_on` date and page on it. Indexing `days` makes
  the external daily `days` update non-HOT.
- **Audit trail** — an `interest_postings` table (deposit, date, amount), partitioned by period
  if it grows.
- **Exact decimal interest maths** — business decision (changes balances by a cent in edge
  cases); contained to strategies + calculator (ADR).
- **ArchUnit** — automated dependency-direction rules once the codebase or team grows. Note:
  `org.ikigaidigital` (calculator) ↔ `domain.interest` (strategies use the frozen `TimeDeposit`)
  is a known, accepted package cycle (ADR 0006) — a cycle rule would need to exclude it.
- **PgBouncer** — when instances × pool size approaches `max_connections`; the design only uses
  transaction-scoped row locks, so it is compatible with transaction pooling.
