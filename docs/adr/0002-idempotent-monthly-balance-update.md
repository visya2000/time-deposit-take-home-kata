# 0002 — Idempotent, concurrency-safe monthly balance update

Status: accepted

## Context

The update endpoint applies *monthly* interest (`rate / 12`) and must be safe to call repeatedly
(client retries, a daily scheduler, several instances). Deposits start on different days, so
interest is due per deposit, not per calendar run. The data set may be large.

## Decision

- A marker column `last_interest_applied_on` (business date, `NULL` = never). A deposit is due
  when the marker is `NULL` or `<= today.minusMonths(1)` — one **calendar month**, not 30 days
  (30-day cycles would post about 12.17 times a year). `today` is taken once per run from a
  `Clock` in the configured business zone.
- The marker is advanced **only when the balance changed**, so deposits in a grace period get
  their first interest as soon as they become eligible.
- The run walks deposits in chunks (default 500), **one transaction per chunk**:
  `SELECT … WHERE id > :afterId AND <due> ORDER BY id LIMIT :n FOR UPDATE SKIP LOCKED`, then the
  frozen calculator, then batched updates of the changed rows. The `id > :afterId` cursor
  guarantees termination (deposits that stay due are not returned again).
- The application layer owns the flow through a small `TransactionRunner` port (Spring-free,
  unit-tested with fakes); the adapter implements it with `TransactionTemplate`.

## Consequences

- A repeated call is a no-op; a failure rolls back only the current chunk, and the next run
  continues without double posting.
- Concurrent runs never process a row twice (`FOR UPDATE SKIP LOCKED`; PostgreSQL re-checks the
  due condition on the latest row version); rows locked by others are processed by the next run.
  Other writers on a locked row wait until the chunk commits (no lost update). Reads never block.
- Missed runs are not back-filled (one posting after an outage, the cycle shifts) — a known
  limitation; whether missed months are owed is a business decision.
- Considered and rejected: a per-month postings ledger (puts every deposit on one calendar cycle,
  grows by deposits × 12 rows per year) and an `Idempotency-Key` header (covers retries only).
