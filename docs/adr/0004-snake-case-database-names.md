# 0004 — snake_case database names

Status: accepted

## Context

The README names the tables and columns in camelCase (`timeDeposits`, `planType`,
`timeDepositId`). PostgreSQL folds unquoted identifiers to lower case:
`CREATE TABLE timeDeposits (planType …)` creates `timedeposits` / `plantype`. Keeping the camelCase
requires double quotes in every migration and query.

## Decision

Use the PostgreSQL convention: `time_deposits`, `withdrawals`, `plan_type`, `time_deposit_id`, …
The API keeps the README's camelCase field names. Spring Boot's naming strategy maps entity fields
(`planType` → `plan_type`) without explicit column names.

## Consequences

- No quoting anywhere; idiomatic SQL, including the native locking query.
- A deliberate deviation from the README's spelling, justified in the migration and listed in the
  assumptions.
- Rejected: quoted camelCase (spec-exact, error-prone) and unquoted camelCase (silently
  lower-cased).
