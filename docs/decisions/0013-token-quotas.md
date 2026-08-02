# ADR 0013 — Per-user daily token and cost quotas

**Status:** accepted

## Context

Corpus has measured token usage and estimated cost since the first milestone,
but measurement is not a control. A Micrometer counter is an *export*: sampled,
aggregated across replicas, and reset on restart. Nothing can be enforced from
it, and the gap matters most exactly where the project is heading — a publicly
reachable demo where anyone can spend the owner's API budget.

Rate limiting (ADR 0006) bounds requests per minute. It does not bound spend: a
user within the request limit can still send maximum-length questions all day.

## Decision

Durable per-user daily ceilings on both tokens and estimated cost, enforced on
every path that can call a chat model.

### A calendar day, not a rolling window

A rolling window needs per-request history to expire out of it. A UTC calendar
day is one primary key and one upsert — and "you get a fresh budget at midnight
UTC" is a rule a user can reason about without reading the implementation. The
clock bean is explicitly `Clock.systemUTC()`; a fleet rolling over at different
local midnights would give some users two budgets in a day and others none.

### Accrual is one statement

`INSERT ... ON CONFLICT DO UPDATE` against `(user_id, usage_date)`. There is no
read-modify-write for two replicas to interleave, so the counter is correct
across the fleet without a lock or a leader — the multi-replica property comes
free from the schema rather than from coordination.

### Pre-check, post-accrue, bounded overshoot

The check runs before the work starts; the accrual after it finishes. A user can
therefore exceed their budget by at most one response. Closing that gap would
mean holding a lock across an LLM call — trading a bounded, documented overshoot
for an unbounded one under contention. The overshoot is stated here rather than
engineered away.

### 429, not 402

RFC 9110 reserves 402 Payment Required, and this is not a payment failure. The
refusal carries the distinct code `token_quota_exhausted` so a client can tell
"slow down" (rate limit) from "you are out for today" — the two have completely
different remedies and would otherwise be indistinguishable 429s.

The streaming path checks **before the `SseEmitter` is constructed**. Once an
emitter is returned the status line is already 200, and the client would have to
parse an error event to discover it was refused.

### What does not consume budget

Accrual happens in `recordUsage`, the single point where both the streaming and
MCP paths converge *after real spend*. A cache hit (ADR 0012) never reaches it,
and neither does a 503 from an unconfigured or circuit-broken model. A request
that cost nothing does not consume budget.

## Consequences

- The realistic failure mode of a quota on a public demo is not overspend, it is
  a reviewer hitting a wall three questions in. Four mitigations, deliberately:
  a generous default (1M tokens / $5 per day), a much higher ceiling in the free
  `local` profile where Ollama makes tokens cost nothing, per-user overrides in
  `user_quotas` that raise one user without restating the rest, and
  `CORPUS_QUOTA_ENABLED=false` as a single-variable kill switch.
- `GET /api/usage` reports today's spend, the limits in force, what remains, and
  whether enforcement is on. A quota a user cannot observe is indistinguishable
  from the service being broken.
- `corpus_quota_blocked_total` shows refusals, which is the signal that a limit
  is set too low rather than that abuse is being stopped.
- A failed accrual is logged and swallowed. Losing an accrual understates spend;
  failing a request the user has already paid for is worse.
- `token_usage` is also a per-user spend history the metrics pipeline cannot
  provide, since it survives restarts and is not downsampled.
