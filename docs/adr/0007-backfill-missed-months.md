# 0007 — Back-fill missed months

Status: accepted (extends 0002)

## Context

With ADR 0002 a run posted at most one month per deposit and set the marker to the run date. After
a scheduler outage, a row skipped because another transaction held it, or for a deposit that was
never posted, owed months were lost and the deposit's monthly cycle shifted.

## Decision

A run produces **exactly what daily runs would have produced** since the deposit's last posting —
or, for a deposit never posted, since the first day it was eligible.

- **Due dates**: `next(d)` is the same day one month later, or the 1st of the following month if
  that day does not exist (Jan 31 → Mar 1 → Apr 1). This is exactly when the existing due rule
  (`marker <= today.minusMonths(1)`) makes a deposit due again, so the SQL filter is unchanged.
  Plain `marker + n months` would not match it (Jan 31 + 1 month = Feb 28 → two postings in March).
- **Never posted**: the first due date is the first day after opening (`today − days`) on which the
  frozen calculator returns interest for the deposit's age on that day — found by asking the
  calculator, so no plan rule is duplicated (basic/student day 31, premium day 46).
- **Each due date** is posted by the frozen calculator on a snapshot with the deposit's **age on
  that date** (`days − days since the due date`) and the balance after the previous posting,
  **re-rounded to cents** with the domain helper `Cents` — as an on-time run stores it. Without the
  re-rounding, back-filled premium balances differ by a cent in 6,091 of 10M cases after two months.
- **Marker** = the last due date that earned interest, as daily runs mark only postings that
  changed the balance. **No cap** on months per run: the interest is owed either way.
- Ports: `lockNextDue` returns each deposit with its marker (`DueTimeDeposit`);
  `saveAppliedInterest` takes `InterestPosting(depositId, balance in cents, marker)`.

## Consequences

- Daily runs give the same results as before; late runs no longer lose months and cycles keep
  their dates. Idempotency, locking and per-chunk transactions are unchanged.
- **First deployment**: every marker is `NULL`, so the first run back-fills every month since each
  deposit became eligible. This assumes existing balances contain no interest yet; otherwise the
  markers must be set by a data migration first.
- Withdrawals between missed due dates are unknown per date: back-filled months compound from the
  current balance. The plan type is assumed constant; months that earned nothing are re-evaluated
  on later runs, so a plan type or balance changed by another system would earn interest for them
  retroactively.
- Assumes each plan's eligibility is one contiguous age window (true for basic, student, premium);
  a plan eligible again after a pause would be back-filled only on its monthly due dates.
- The `last_interest_applied_on` comment in the V1 migration ("date interest was last applied")
  predates this decision; the column now holds the scheduled due date of the last posting. The
  applied migration is not edited (Flyway checksum).
- Daily-run equivalence holds for balances and markers, and is tested against a day-by-day
  simulation, including a student deposit crossing day 365.
- A never-posted, never-eligible deposit (e.g. unknown plan) is checked day by day since opening
  on every run — cheap in-memory calls, accepted.
