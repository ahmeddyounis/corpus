# Observability for LLM applications: tokens, cost, and latency phases

Traditional service metrics — request rate, error rate, duration — miss what
actually hurts in an LLM application: token spend that scales with prompt
size, latency dominated by model inference, and retrieval quality that decays
silently. Corpus instruments all three with Micrometer and exposes them on a
Prometheus endpoint.

## Token and cost accounting

Every model call reports its usage: prompt tokens in, completion tokens out.
Corpus records both as counters tagged by provider, model, and direction
(`corpus_llm_tokens_total`). A configurable price table — dollars per million
tokens for each model — turns usage into an estimated spend counter,
`corpus_llm_cost_estimate_usd_total`. The word "estimate" is honest: prices
change, cached tokens may bill differently, and local Ollama models cost
zero. The point is trend and attribution, not accounting-grade precision: a
dashboard that shows cost per model per hour catches a prompt regression that
doubles token usage the day it ships, not when the invoice arrives.

## Phase latency, not just total latency

A RAG request has phases with different failure modes, so Corpus times each
one: `embedding` (turning content into vectors during ingestion),
`retrieval` (the parallel hybrid search), `first_token` (time until the
model starts answering — the latency a human actually feels), and
`full_response` (the complete stream). Each phase is a Micrometer timer with
histogram buckets, so Prometheus can compute p50/p95/p99 per phase. When
total latency degrades, the phase breakdown says where: a slow `retrieval`
p95 points at the database or an oversized candidate set, while a slow
`first_token` with fast retrieval points at the model provider or prompt
size.

## Retrieval quality signals in production

Offline evals gate the build, but production queries drift away from any
golden set. Corpus exports two cheap live signals: the top fused score of
each retrieval (`corpus_retrieval_top_score`) and the spread between the top
and bottom returned scores. A falling top-score trend means queries are
landing far from the indexed corpus — new topics, new jargon, or an embedding
model mismatch after a bad deploy. A collapsing spread means the ranker is
returning near-ties, which usually precedes "the answers got vague"
complaints. Neither replaces evals; both are early-warning gauges that cost
nothing per request.

## Dashboards and the monitoring stack

The compose file ships an optional monitoring profile: Prometheus scrapes
`/actuator/prometheus` and Grafana provisions a prebuilt dashboard with
panels for estimated cost, token throughput, phase-latency percentiles,
retrieval-quality gauges, request rate, and HTTP 429 volume from the rate
limiter. Everything is standard Micrometer + Prometheus + Grafana — no
vendor-specific LLM observability service — because the interesting work is
choosing what to measure, not where to send it.

## What is deliberately not measured

Corpus does not log prompt or completion text into metrics systems: content
is user data, and observability pipelines are the easiest place to leak it.
Metrics carry numbers and low-cardinality tags only. When content-level
debugging is needed, it belongs in short-retention application logs with
access controls, never in a time-series database that lives forever.
