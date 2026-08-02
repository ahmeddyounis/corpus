# Service level objectives

These are the numbers Corpus is operated against. They exist to make one
decision easy: *is this worth waking someone up for?* An objective nobody would
defend at 3am is not an objective, it is a wish — so there are four, not twenty.

Every alert in [`deploy/helm/corpus/files/alerts.yml`](../deploy/helm/corpus/files/alerts.yml)
traces back to one of these, and every one links to a section of
[the runbook](runbook.md).

| Objective | Target | Window | Measured by |
|---|---|---|---|
| **Availability** | 99% of requests non-5xx | 30 days rolling | `http_server_requests_seconds_count{status=~"5.."}` |
| **Retrieval latency** | p95 < 500 ms | 10 min | `corpus_rag_phase_seconds{phase="retrieval"}` |
| **Time to first token** | p95 < 5 s | 10 min | `corpus_rag_phase_seconds{phase="first_token"}` |
| **Cost** | < $1/hour estimated | 1 hour | `corpus_llm_cost_estimate_usd_total` |

## Error budget

99% over 30 days is **7h 12m** of unavailability per month. Alerting is
multi-window, multi-burn-rate rather than a static threshold:

| Burn rate | Budget consumed in | Long window | Short window | Severity |
|---|---|---|---|---|
| 14.4x | ~2 days | 1 h | 5 m | page |
| 6x | ~5 days | 6 h | 30 m | ticket |

Both windows must be burning for the alert to fire. The long window establishes
that the burn is real rather than a blip; the short window establishes that it
is *still happening*, so an incident that has already recovered stops paging on
its own. A single static "error rate > 1%" threshold cannot distinguish a
two-minute deploy hiccup from a sustained outage, and pages for both.

## What is deliberately not an objective

- **Answer quality.** It is gated in CI against the golden set (recall@3, MRR,
  nDCG@5, plus non-regression against the fusion-only head), not alerted on in
  production, because there is no ground truth for a live user's question.
  `CorpusRetrievalDegraded` is the closest production proxy and watches the
  *score distribution*, not correctness.
- **Ingestion latency.** Uploads are explicitly asynchronous with a 202 and a
  status field. What is alerted on is a backlog that never drains, which is a
  stuck sweeper — a different failure with a different fix.
- **Circuit-breaker state as availability.** A provider outage trips every
  replica at once. Serving `/api/search` and `/api/documents` normally while
  `/api/chat` returns 503 is a *correct* degraded mode, not downtime, which is
  why the breaker is not in the readiness group. It has its own page.

## Interpreting the objectives together

The failure modes are easier to tell apart in pairs:

- Retrieval slow, first token fine → the database, not the provider. Check the
  Hikari pool and `statement_timeout`.
- First token slow, retrieval fine → the provider, or reranking sitting in front
  of it. Check `corpus_retrieval_rerank_seconds` before blaming the model.
- Both fine, errors climbing → not latency at all. Look at the breaker and the
  429 rate.
- Cost climbing with flat token throughput → a model swap, not more traffic. The
  price table in `corpus.pricing.models.*` may also simply be out of date.
