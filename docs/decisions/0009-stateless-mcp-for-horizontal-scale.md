# ADR 0009 — Stateless MCP transport in deployed profiles

**Status:** accepted

## Context

The streamable-HTTP MCP transport assigns an `Mcp-Session-Id` during the
`initialize` handshake and keeps the session in a `ConcurrentHashMap` inside the
transport provider — per JVM. With N replicas behind a load balancer and no
session affinity, roughly (N−1)/N of MCP requests land on an instance that has
never seen the session and fail with "Session not found". That makes the MCP
surface — the project's headline integration — the one thing that breaks first
when you scale out.

Options considered:

- **Sticky sessions at the ingress.** Fly's proxy cannot do it at all, and
  nginx-ingress cannot cleanly hash an arbitrary custom header. Rejected.
- **Pin `replicaCount: 1` permanently.** Honest, but it caps the deployment story
  and makes the HPA in the Helm chart decorative. Rejected as the only answer.
- **Switch the transport to stateless.** Spring AI ships
  `WebMvcStatelessServerTransport`, selected by
  `spring.ai.mcp.server.protocol=STATELESS`, with the same `@McpTool` annotation
  scanning. Chosen.

## Decision

Deployed profiles (`cloud`, `keyless`) run `spring.ai.mcp.server.protocol=STATELESS`.
`local` and `test` keep `STREAMABLE`, so the session handshake stays exercised by
the existing `McpIntegrationTest` and a rollback is a one-property change.

## Consequences

- Any replica can serve any MCP call; no sticky sessions, no session store.
- This is what makes `replicaCount: 2` a safe default in the Helm chart.
- **Given up:** server→client notifications and long-lived server-initiated
  streams (tool-list-changed, progress, logging). All three Corpus tools are
  request/response, so nothing currently offered is lost. Adding a genuinely
  streaming or progress-reporting tool would mean revisiting this.
- Verified rather than assumed: `McpStatelessIntegrationTest` drives `tools/list`
  and `tools/call` with no `Mcp-Session-Id` header at all, and asserts that a
  bearer token still scopes results to that user — proving `McpUserResolver`'s
  `SecurityContextHolder` read still works on the stateless transport's request
  thread, which was the main risk of the switch.
