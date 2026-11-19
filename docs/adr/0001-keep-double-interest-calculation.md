# 0001 — Keep `double` arithmetic in the interest calculation

Status: accepted

## Context

`TimeDeposit.balance` is a `Double` in a frozen shared class, and the README declares the existing
`updateBalance` correct ("ensure its behavior remains unchanged"). The calculation is
`balance * rate / 12` in `double`, rounded with `new BigDecimal(double).setScale(2, HALF_UP)`.

Exact decimal maths gives different results at rounding edges. Measured over every balance
0.01–100,000.00: 3,019 (basic), 17,069 (student) and 10,216 (premium) of 10M balances differ by
one cent. Example: basic 18.00 → current code 18.01 (`18 * 0.01 / 12` = `0.01499999…`), exact
maths 18.02.

## Decision

- The strategies keep the original `double` expressions and operand order; the calculator keeps
  the original rounding. Characterization tests pin rounding edges per plan (18.00 → 18.01,
  30.00 → 30.02, 10.00 → 10.02, 162.00 → 162.67) that also catch reordered expressions.
- `BigDecimal` everywhere else: database `NUMERIC(19,2)`, API, read models. `double` exists only
  transiently inside one calculation; the persistence adapter re-quantises every write with
  `BigDecimal.valueOf(d).setScale(2, HALF_UP)`, so floating-point error never accumulates.

## Consequences

- Behaviour is identical to the original code.
- Tech debt: cents are exact in `double` only up to about 9×10¹³. Moving to exact decimal maths
  is a business decision (it changes customer balances) and is contained to the strategies and
  the calculator.
