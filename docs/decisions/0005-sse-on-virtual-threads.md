# ADR 0005 — SSE streaming via SseEmitter on virtual threads (Web MVC, not WebFlux)

**Status:** accepted

## Context

Chat answers must stream token-by-token, then deliver citations and usage
stats as trailing events. Spring AI exposes streaming as a Reactor `Flux`.
The alternatives were a WebFlux stack (reactive end to end) or Web MVC with
explicit emitters.

## Decision

Web MVC with virtual threads (`spring.threads.virtual.enabled=true`). The
controller returns an `SseEmitter` (5-minute timeout); a virtual-thread
worker runs retrieval, then blocking-iterates the `Flux` via `toIterable()`,
sending named events in order: `token`* → `citations` → `usage` → `done`,
with failures surfaced as an `error` event before completing. Usage metadata
is captured from the last stream chunk that carries non-zero token counts.

## Consequences

- Imperative, debuggable pipeline code with plain stack traces; Reactor is
  confined to one iteration site.
- Explicit event sequencing (trailing citations/usage) is trivial compared
  with concatenating fluxes.
- Virtual threads absorb the blocking iteration cheaply; RAG concurrency is
  I/O-bound fan-out, exactly their sweet spot.
- MVC's SSE requires the client to tolerate one response stream per request
  (no server push multiplexing) — appropriate for this API shape.
