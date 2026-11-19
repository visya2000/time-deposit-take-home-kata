# 0003 — Single-statement GET with optional keyset pagination

Status: accepted

## Context

`GET /time-deposits` returns deposits with their withdrawals. Two statements (deposits, then
withdrawals) could mix two points in time under PostgreSQL's `READ COMMITTED` (read skew) and
invite N+1 queries. Returning everything does not scale.

## Decision

- Each request is **one SQL statement**: an HQL fetch join ordered by deposit and withdrawal id.
  The paginated variant limits deposit ids in a subquery
  (`WHERE d.id IN (SELECT … WHERE id > :afterId ORDER BY id LIMIT :limit)`), so the limit counts
  deposits, not joined rows, and Hibernate never paginates in memory
  (`fail_on_pagination_over_collection_fetch` makes it fail instead). A test asserts exactly one
  statement in both modes.
- Pagination is optional and keyset-based: no parameters → all deposits (README behaviour);
  `limit` (1–1000) and/or `afterId` → one page; `Link: </time-deposits?afterId=…&limit=…>;
  rel="next"` when more data exists. Same array body in both modes, no total count.
- The `Link` target is path-absolute: an absolute URL built from the request would take
  scheme and host from headers (wrong behind a proxy) and re-encode other parameters; a
  query-only reference is mis-resolved by some clients (`java.net.URI.resolve`). Trade-off: it is
  relative to the server root, so a context path or proxy path prefix is not included.

## Consequences

- Every response is one consistent snapshot; no read locks, never blocked by the update run.
- Constant cost per page and no duplicates or skips while data changes; no "jump to page N".
- `LIMIT` in an HQL subquery is Hibernate-specific (supported by PostgreSQL, not by e.g. MySQL).
