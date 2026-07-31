# ADR 0001 — Modular monolith over microservices

**Status:** accepted

## Context

Corpus spans six concerns (ingestion, retrieval, chat, MCP, ops, security)
that could each be a service. The system must stay runnable by one person with
one command, remain reviewable in one sitting, and still demonstrate where
real service boundaries would fall.

## Decision

One Spring Boot deployable with strict package boundaries:
`ingestion`, `retrieval`, `chat`, `mcp`, `ops`, `security` under the base
package. Modules interact only through public service APIs — never another
module's repositories or tables. Cross-cutting configuration lives in
`config`.

## Consequences

- `docker compose up` runs the entire system; stack traces and refactors stay
  whole-program.
- The hot path avoids network hops whose latency would be dwarfed by model
  inference anyway.
- The seams are explicit: ingestion (already asynchronous, DB-coupled) is the
  first extraction candidate, retrieval second. The MCP module is a thin
  adapter and moves with chat/retrieval.
- Discipline is social, not compiler-enforced; a module-boundary violation is
  visible in review (imports from another module's internals) but not a build
  failure. ArchUnit rules would harden this if the codebase grew maintainers.
