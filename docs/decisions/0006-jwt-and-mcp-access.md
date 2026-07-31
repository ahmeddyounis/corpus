# ADR 0006 — Stateless HS256 JWTs everywhere; MCP shares the same identity model

**Status:** accepted

## Context

The REST API needs per-user isolation. The MCP endpoint must be reachable by
Claude Desktop with minimal friction locally, yet must never widen access
beyond what the REST API allows the same caller.

## Decision

Spring Security resource-server JWT with a symmetric HS256 secret
(`CORPUS_JWT_SECRET`, ≥256 bits). `/api/auth/token` issues 24-hour tokens
(`sub` = user UUID); every query filters by that UUID at the storage layer
(SQL predicates and vector filter expressions), making authorization
structural rather than controller-checked. Per-user Bucket4j token buckets
(`CORPUS_RATE_LIMIT_RPM`) return 429 + `Retry-After` past budget.

`/mcp` is in the same filter chain: a bearer token authenticates the tool
call as that user. When `corpus.mcp.anonymous-user` is set (local/keyless
profiles only) unauthenticated MCP calls act as the demo account for
zero-config Claude Desktop connects; cloud profiles leave it unset, making a
token mandatory.

## Consequences

- Statelessness: any instance validates any token; no session storage.
- One identity model across REST and MCP — verified by an integration test
  proving bearer-scoped MCP tool calls see only that user's chunks.
- Symmetric signing is the right simplicity for a single service; moving to
  RS256 + JWKS is a configuration change when a second validator appears.
- Pure stateless tokens cannot be revoked individually; the 24h TTL bounds
  exposure and is the accepted trade.
