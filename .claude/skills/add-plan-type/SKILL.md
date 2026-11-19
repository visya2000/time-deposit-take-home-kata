---
name: add-plan-type
description: Add a new time deposit interest plan (e.g. "gold, 7% p.a., interest after 90 days") via the InterestStrategy extension point, test-first. Use when a new plan type or a change to a plan's interest rule is requested.
argument-hint: <plan name> <annual rate> <eligibility rules>
---

# Add a new interest plan

Request: $ARGUMENTS

Existing plans must keep their exact behaviour — never edit another plan's strategy or the
calculator's rounding to make a new plan fit.

1. **Clarify the rule.** Restate it as precise boundaries (e.g. "interest when days > 90").
   Decide explicitly whether the 30-day grace period applies (README: it applies to *existing*
   plans). The back-fill of missed months (ADR 0007) assumes the ages at which a plan pays
   interest form **one contiguous window**; after a pause such a plan would be back-filled only on
   the deposit's existing monthly due dates, so raise it before implementing. If anything is
   ambiguous, ask before writing code.
2. **Tests first** (use the `test-writer` subagent): a strategy unit test in
   `java/src/test/java/org/ikigaidigital/domain/interest/` covering one below / at / one above
   every boundary, plus one calculator-level case proving the plan is registered.
   Run `cd java && ./mvnw test` and confirm the new tests fail for the right reason.
3. **Implement** `<Name>InterestStrategy` in
   `java/src/main/java/org/ikigaidigital/domain/interest/`, implementing `InterestStrategy`:
   `planType()` returns the stored plan string; `monthlyInterest` uses
   `balance * rate / 12` in `double`, unrounded (the calculator rounds).
4. **Register** it in `InterestStrategies.defaults()` — the single registration point used by
   both the no-arg calculator and the Spring configuration — and add the plan type to the
   expected list in `InterestStrategiesTest` (it pins the exact set of default plans).
5. **Verify**: `./mvnw test`, then `./mvnw verify` if Docker is available.
6. **Review**: run the `architecture-reviewer` subagent on the diff and fix findings.
7. **Document**: note the new plan in the project README's plan table, and add a line to
   `docs/AI_USAGE.md` if done with AI assistance.
