# CLAUDE.md

Time Deposit refactoring kata. **Only the `java/` module is in scope** — ignore `c#/`, `kotlin/`,
`python/`, `typescript/`. Requirements: `README.md`. Agreed plan and decisions: `PLAN.md`
(source of truth — follow it, and update it when a decision changes).

## Hard constraints (never violate)

- `java/src/main/java/org/ikigaidigital/TimeDeposit.java` is a frozen shared contract: no edits
  of any kind (a hook blocks Edit/Write; do not work around it via shell commands).
- `TimeDepositCalculator`:
  - keep `public void updateBalance(List<TimeDeposit>)` signature and the no-arg constructor;
  - its observable behaviour must stay identical (README: "functioning correctly");
  - keep `double` arithmetic and the existing operand order (`balance * rate / 12`) and rounding
    (`new BigDecimal(double).setScale(2, HALF_UP)`) — switching to BigDecimal maths changes results.
- Exactly two REST endpoints: `GET /time-deposits` (optional keyset pagination `limit`/`afterId`,
  no params → all), `POST /time-deposits/balance-updates` (204).
- GET must remain a single SQL statement (one snapshot, no read skew) — see PLAN.md §3.
- Database is PostgreSQL; tables/columns are snake_case (`time_deposits`, `plan_type`, ...);
  API field names stay camelCase. Persistence: Spring Data JPA — HQL fetch join for the GET,
  native SQL for the locking update query, `spring.jpa.open-in-view=false`; JPA entities stay in
  the persistence adapter; `WithdrawalEntity` has no back-reference.
- Balance update must stay idempotent and concurrency-safe (PLAN.md §3): due = marker NULL or
  `<= today.minusMonths(1)`; single-threaded chunks with keyset cursor `id > :afterId` +
  `FOR UPDATE SKIP LOCKED` (locking query selects all columns); marker advanced only when the
  balance changed; `today` from the injected business-zone `Clock`.
- Interest strategies are registered only in `InterestStrategies.defaults()`.
- Demo seed data lives in a separate Flyway location for the `local` profile; tests start empty.
- Invalid input / exception handling is out of scope — do not add it.

## Architecture (hexagonal, single Maven module)

```
org.ikigaidigital                 TimeDeposit, TimeDepositCalculator (stay here)
org.ikigaidigital.domain          Withdrawal, read models, interest/ strategies — plain Java
org.ikigaidigital.application     port/in (use cases), port/out (repository), services
org.ikigaidigital.adapter.in.web  controller implementing the generated OpenAPI interface
org.ikigaidigital.adapter.out.persistence  JPA entities, Spring Data repos, mappers, TransactionRunner
```

- Dependencies point inward: adapter → application → domain. Domain imports no Spring/JPA.
- Convert `Double` ↔ `BigDecimal` only at the adapter boundaries.
- New interest plan = new `InterestStrategy` class + registration (use `/add-plan-type`).
- No abstraction without a clear benefit. Do not over-engineer.

## Commands (run from `java/`)

Java level is 17 (existing `pom.xml`); the local JDK may be newer — do not use post-17 APIs.


- `./mvnw test` — unit tests (`*Test.java`), no Docker needed.
- `./mvnw verify` — everything incl. Testcontainers tests (`*IT.java`); needs Docker running.
- `docker compose up -d` — local PostgreSQL.

## Workflow

1. Work one `PLAN.md` step at a time; propose the approach before larger changes.
2. Tests first. Characterization tests guard existing behaviour before any refactoring.
3. Tests: behaviour and outcomes, boundaries (e.g. 30/31, 45/46, 365/366), descriptive names,
   meaningful assertions. No mocking frameworks in domain/application tests — use in-memory fakes.
4. Small steps; run the relevant tests after each change.
5. Before finishing a step: run the `architecture-reviewer` subagent on the diff and fix findings.
6. Do not commit or push unless the user explicitly asks. Commits are atomic, one plan step each.
7. Remove dead code, unused methods and variables. Every line must be explainable.
8. Record notable AI-assisted work in `docs/AI_USAGE.md`.
