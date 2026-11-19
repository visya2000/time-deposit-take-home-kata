# XA Bank Time Deposits — Java solution

Spring Boot 4 / Java 17 / PostgreSQL implementation of the [kata](../README.md): the existing
interest logic refactored behind an extension point (behaviour unchanged), a database, and exactly
two REST endpoints described by an OpenAPI contract.

- Contract: [`src/main/resources/static/openapi/time-deposits.yaml`](src/main/resources/static/openapi/time-deposits.yaml)
- Design decisions: [`../docs/adr/`](../docs/adr/) · full plan: [`../PLAN.md`](../PLAN.md)
- AI-assisted development: [`../docs/AI_USAGE.md`](../docs/AI_USAGE.md)

## Prerequisites

- JDK 17 or newer (the build compiles with `--release 17`)
- Docker (PostgreSQL for local runs and for the integration tests)
- No Maven installation needed: use the Maven Wrapper (`./mvnw`, on Windows `mvnw.cmd`)

All commands below run from this `java/` directory.

## Run locally

```bash
docker compose up -d                                   # PostgreSQL 18 on localhost:5432
./mvnw spring-boot:run "-Dspring-boot.run.profiles=local"   # quotes needed in PowerShell
```

The `local` profile connects to the compose database and loads demo data (10 deposits covering
every plan and the interest boundaries, some with withdrawals). Without a profile, configure the
database with `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.

Stop with `Ctrl+C`; `docker compose down -v` removes the database including its data.

## Trigger the endpoints with Swagger UI

Open **http://localhost:8080/swagger-ui.html** — it shows the contract file itself.

1. **List deposits** — expand `GET /time-deposits` → *Try it out* → *Execute*. Without parameters
   all deposits are returned with `id`, `planType`, `balance`, `days` and `withdrawals`.
2. **Paginate (optional)** — enter `limit` (1–1000) and/or `afterId`, *Execute*. If more deposits
   exist, the response header `Link: </time-deposits?afterId=…&limit=…>; rel="next"` contains the
   next page; enter its `afterId` and `limit` to continue. Swagger UI rejects a `limit` outside
   1–1000 before sending; sent directly (e.g. `curl -i "http://localhost:8080/time-deposits?limit=0"`)
   the server answers `400`.
3. **Update balances** — expand `POST /time-deposits/balance-updates` → *Try it out* →
   *Execute* → `204`.
4. Run step 1 again: every deposit got the interest it is owed since it became eligible (missed
   months are back-filled — the demo data was never posted). E.g. `basic` 31 days 1200.00 →
   1201.00, `student` 31 days 1200.00 → 1203.00, `premium` 46 days 1200.00 → 1205.00 (one month
   each); the `student` deposits of 365 and 366 days get every month from day 31 until the plan
   ends after one year — 11 or 12 postings depending on the run date (1200.00 → 1233.43 or
   1236.51). Deposits in their grace period and the unknown plan `gold` are unchanged.
5. Run step 3 again: nothing changes — interest is posted at most once per calendar month per
   deposit.

**Re-run the interest on the same day** (demo only — it posts all owed months again, as if the
deposits had never received interest):

```bash
docker compose exec postgres psql -U time_deposits -d time_deposits \
  -c "UPDATE time_deposits SET last_interest_applied_on = NULL;"
```

The same with curl (in Windows PowerShell use `curl.exe`):

```bash
curl -i "http://localhost:8080/time-deposits?limit=3"
curl -i -X POST http://localhost:8080/time-deposits/balance-updates
```

## Tests

```bash
./mvnw test     # unit tests (*Test), no Docker
./mvnw verify   # + integration tests (*IT) with Testcontainers PostgreSQL; needs Docker
```

Coverage report (a guide, no gate): `target/site/jacoco/index.html` after `./mvnw verify`.
CI runs `./mvnw verify` on every push to `main` and on pull requests.

| Suite | What it covers |
|---|---|
| `TimeDepositCalculatorTest` | Characterization of the original `updateBalance`: every plan boundary, rounding edges where `double` differs from exact decimal maths, unknown plans |
| Strategy / service unit tests | Each plan's rule; a custom plan added through the extension point (`TimeDepositCalculatorStrategiesTest`); the balance-update run with in-memory fakes (chunks, idempotency, calendar months, failures, time zones, back-filling missed months); `InterestSchedule`, `Cents` |
| Persistence ITs | Schema, seed, single-statement GET, keyset pagination, due filter in SQL, markers per deposit |
| `BalanceUpdateIT` | The balance update end to end over PostgreSQL: eligibility, back-fill of missed months, idempotent re-run |
| `BalanceUpdateConcurrencyIT` | Parallel runs, non-blocking reads, `SKIP LOCKED`, no lost update |
| `BalanceUpdateRollbackIT` | A database failure in the middle of a chunk rolls back the whole chunk in PostgreSQL (incl. an UPDATE already executed); earlier chunks stay committed; a re-run posts the rest once |
| `TimeDepositApiIT` | Both endpoints over HTTP, `Link` header, validation, Swagger UI |

## Architecture

Hexagonal, one Maven module; dependencies point inwards. `TimeDeposit` and
`TimeDepositCalculator` stay in the root package as a shared kernel — moving them would be a
breaking change ([ADR 0006](../docs/adr/0006-shared-kernel-in-root-package.md)).

```
org.ikigaidigital                  TimeDeposit (frozen), TimeDepositCalculator (signature/behaviour kept)
├── domain                         read models; interest/ strategies — plain Java
├── application                    use cases (port/in), repository + transaction ports (port/out),
│                                  TimeDepositService (orchestration), InterestBackfill (owed months)
└── adapter
    ├── in/web                     controller implementing the interface generated from the contract
    ├── out/persistence            JPA entities, Spring Data repository, adapter, TransactionRunner
    └── config                     Clock (business zone), calculator and service beans
```

- **Interest**: one `InterestStrategy` per plan, registered only in `InterestStrategies.defaults()`.
  A new plan is one new class plus one registration line (`/add-plan-type` skill). The calculator
  keeps the original `double` expressions and rounding — results are identical ([ADR 0001](../docs/adr/0001-keep-double-interest-calculation.md)).
- **Balance update**: deposits are processed in chunks (default 500, `interest.update.chunk-size`),
  one transaction per chunk, locked with `FOR UPDATE SKIP LOCKED`; a per-deposit marker makes it
  idempotent and safe under concurrent calls ([ADR 0002](../docs/adr/0002-idempotent-monthly-balance-update.md));
  a late run back-fills every missed month as daily runs would have
  ([ADR 0007](../docs/adr/0007-backfill-missed-months.md)).
- **GET**: one SQL statement per request (consistent snapshot, no N+1), optional keyset pagination
  ([ADR 0003](../docs/adr/0003-single-statement-get-with-keyset-pagination.md)).
- **Database**: Flyway migrations, snake_case names ([ADR 0004](../docs/adr/0004-snake-case-database-names.md)).
- **API**: contract first; Swagger UI is a static page showing the contract
  ([ADR 0005](../docs/adr/0005-contract-first-api-and-static-swagger-ui.md)).

## Interest rules (unchanged behaviour)

Monthly interest = `balance × annual rate / 12`, rounded half-up to cents.

| Plan | Annual rate | Earns interest when |
|---|---|---|
| `basic` | 1 % | days > 30 |
| `student` | 3 % | 30 < days ≤ 365 |
| `premium` | 5 % | days > 45 |
| anything else (incl. `Basic`) | — | never |

## Assumptions

Ambiguities in the requirements, resolved as follows (also commented in the code):

1. `days` is the deposit's age, maintained by another system and current when the update runs.
2. The update endpoint is triggered **daily** by a scheduler; interest is posted **at most once
   per calendar month per deposit** (due again one calendar month after it was last applied, not
   30 days: each posting is a monthly rate). A second call in the same cycle changes nothing.
   Missed months are owed: a late run (outage, a row locked during a run, a deposit never
   posted) posts every owed month, judging eligibility by the deposit's age on each due date
   and compounding month by month; the deposit keeps its monthly cycle.
3. Withdrawals do not affect interest; whether they change `balance` is not specified.
4. Unknown plan types, including a different case (`Basic`) and `null`, earn no interest.
   (The original code threw a `NullPointerException` for a `null` plan type after 30 days;
   invalid input is out of scope.)
5. Database names are snake_case (`time_deposits`, `plan_type`, …); API names stay camelCase.
6. One extra column, `time_deposits.last_interest_applied_on`, records the scheduled due date of
   the last interest posting; it is not exposed by the API.
7. Business dates are computed in one configured zone (`interest.business-zone`, default `UTC`).
8. Withdrawals are returned as `{ id, amount, date }` (the README does not define their shape).
9. Ids are identity values, so new rows get increasing ids (the keyset cursors rely on this).
10. Existing balances contain no interest yet: the first run back-fills every month since each
    deposit became eligible. If an earlier system already posted interest, set
    `last_interest_applied_on` by a data migration before the first run.

## Known limitations

- **Back-filling uses the current state**: withdrawals between missed due dates are not known
  per date (no balance history), so missed months compound from the current balance; the plan
  type is assumed constant. Months that earned nothing are re-evaluated on later runs, so a
  plan type or balance changed by another system would earn interest for them retroactively.
- **Back-filling assumes one contiguous eligibility window per plan** (true for basic, student
  and premium): a future plan that becomes eligible again after a pause would be back-filled
  only on its monthly due dates, not from the first day it is eligible again.
- **`double` in the calculation**: kept on purpose (exact decimal maths changes results by a cent
  in edge cases); cents are exact only up to about 9×10¹³. Every write re-rounds to cents, so
  floating-point error does not accumulate.
- **Synchronous update**: on very large data sets the HTTP call can time out; the run still
  finishes on the server and a retry is a no-op or shares the work.
- **GET without parameters** loads all deposits into memory — use pagination for large data sets.
- A deposit whose id is not positive (possible only with explicit ids) never receives interest
  (the balance-update cursor starts after id 0) and is missing from paginated pages unless a
  negative `afterId` is passed; it is listed by the GET without parameters.
- Deposits locked by another transaction during a run are skipped and processed by the next run
  (at most one day late, posted for their original due date).
- Deposits that never earn interest (unknown plan, student plan after one year, balances whose
  interest rounds to 0.00) stay due, so every run re-evaluates and briefly locks them; a
  never-posted, never-eligible deposit is checked day by day since opening on every run.
- The `Link` header is relative to the server root; a servlet context path or reverse-proxy path
  prefix would not be included (none is used).

## Possible enhancements

Parallel balance-update workers over disjoint id ranges; asynchronous update (`202 Accepted`);
filtering/paging by `days`; an interest posting audit table; exact
decimal interest maths (a business decision); ArchUnit dependency rules; PgBouncer. Details in
PLAN.md §11.
