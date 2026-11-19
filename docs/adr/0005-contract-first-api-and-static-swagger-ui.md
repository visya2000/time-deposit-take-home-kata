# 0005 — Contract-first API and a static Swagger UI

Status: accepted

## Context

The preferred stack asks for an OpenAPI Swagger contract, and the submission needs Swagger
instructions. The contract should be the single source of truth, and there must be exactly two
endpoints.

## Decision

- The contract (`java/src/main/resources/static/openapi/time-deposits.yaml`) is written by hand.
  `openapi-generator` generates the `TimeDepositsApi` interface and DTOs at build time; the
  controller implements the interface, so code and contract cannot drift (compile error).
- Validation of `limit` (1–1000) comes from the contract via generated bean-validation
  annotations and Spring MVC's built-in method validation (`useSpringBuiltInValidation`) →
  automatic `400`, no hand-written error handling. (With the generator's default class-level
  `@Validated`, violations ended in `500`.)
- Swagger UI is a static page (`static/swagger-ui.html`, swagger-ui webjar) that loads the
  contract file. springdoc was dropped: in version 3.2 its UI cannot run without also generating a
  second spec (`/v3/api-docs`).
- `planType` is a free string, not an enum: unknown plans exist and new plans must not need a
  contract change.

## Consequences

- One spec, served as is; besides the two endpoints only static files are exposed.
- Generated sources live in `target/` and are not committed.
