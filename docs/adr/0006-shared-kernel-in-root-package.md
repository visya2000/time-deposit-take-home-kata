# 0006 — `TimeDeposit` and `TimeDepositCalculator` stay in the root package (shared kernel)

Status: accepted

## Context

The hexagonal layout puts domain code in `org.ikigaidigital.domain`, but the two original classes
live in `org.ikigaidigital`. The README requires no breaking changes, and in particular none to the
shared `TimeDeposit` class or to the `updateBalance` signature. In Java the package is part of a
class's identity: moving either class changes its fully qualified name and breaks every consumer
that imports it (source and binary), so moving is a breaking change.

Consequences of keeping them: `domain.interest` (strategies take a `TimeDeposit`) and the root
package (the calculator uses the strategies) depend on each other, and the application layer's
ports use `TimeDeposit`.

## Decision

The **two classes** form a shared kernel that the domain and application layers may depend on:
`TimeDeposit` is externally owned and frozen; `TimeDepositCalculator`'s public signature and
behaviour are a published contract whose implementation (the strategies behind it) is ours. Both
stay where they are. The strategies keep the full `TimeDeposit` as input, because future interest
rules may need more of the deposit than balance and days. The Spring main class also stays in the
root package (conventional component-scan root); it is not part of the kernel.

Considered: moving the logic into a domain-owned calculator with the old class as a thin facade
and strategies on plain values (removes the cycle without a breaking change). Not chosen: it adds a
mapping layer and narrows the strategy input.

## Consequences

- No breaking change; the frozen contract is untouched.
- The package cycle `org.ikigaidigital` ↔ `domain.interest` is accepted (an ArchUnit cycle rule
  would need to exclude it).
- Suggested future change, documented in `package-info.java` and the calculator's Javadoc, in a
  coordinated breaking release together with the other consumers: move `TimeDeposit` into
  `org.ikigaidigital.domain` and `TimeDepositCalculator` into `org.ikigaidigital.domain.interest`.
  This removes the cycle (nothing in `domain` depends on `domain.interest`); moving both into
  `domain` would only turn it into a `domain` ↔ `domain.interest` cycle.
