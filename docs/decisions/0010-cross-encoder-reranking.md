# ADR 0010 — In-process ONNX cross-encoder reranking after RRF

**Status:** accepted

## Context

Reciprocal Rank Fusion merges the vector and full-text rankings by *position
agreement*: a chunk scores well because both legs ranked it highly. It never
sees the query and a chunk together, so it cannot distinguish a passage that
shares vocabulary with the question from one that answers it.

That is exactly the failure the distractor documents added in ADR 0007's
hardened golden set provoke, and it showed up as a measured ceiling:
`recall@1 = 0.688` with two cases missing entirely, both losing to a distractor
that used the same words as the real source.

The obvious remedy — ask an LLM to rerank — costs an API call per query, which
would make retrieval quality depend on a key, a budget, and a provider's
latency. Corpus already runs an ONNX model in-process for embeddings.

## Decision

A `Reranker` seam sits between fusion and the returned window, with a
`ms-marco-MiniLM-L-6-v2` cross-encoder (Apache-2.0, official ONNX export,
single-logit sequence-pair head) as the default implementation. It runs
in-process through `OrtSession` and DJL's tokenizer, fetched once via Spring
AI's `ResourceCacheService` into the same cache directory the embedding model
already uses — so it is keyless, needs no new infrastructure, and rides the
ONNX cache that CI and the container image already have.

The seam takes the **whole fused candidate set**, not a pre-truncated top-k.
A reranker handed the top-k could only reorder results the first stage already
accepted, which is precisely the mistake it exists to correct.

### Tuning, from measurement rather than intuition

Measured on 14 cores over the 32-case golden set, warmed:

| Configuration | recall@1 | MRR | nDCG@5 | ms/query |
|---|---|---|---|---|
| Fusion only | 0.688 | 0.792 | 0.814 | 3.8 |
| Rerank, `max-length: 256`, `concurrency: 2` | **0.813** | **0.883** | **0.879** | 235 |
| Rerank, `max-length: 128` | 0.625 | 0.747 | 0.777 | 104 |
| Rerank, `max-candidates: 20` | 0.813 | 0.883 | 0.879 | 240 |
| Rerank, `concurrency: 7` | 0.813 | 0.883 | 0.879 | 675 |

Three findings decided the defaults:

- **128 tokens is worse than not reranking at all.** Truncating the passage to
  roughly 120 tokens after the query costs more signal than the cross-encoder
  adds, at every metric. It is 2.3x cheaper and strictly harmful. `max-length`
  stays at 256.
- **`max-candidates` is not the latency lever here.** Capping at 20 changed
  neither quality nor latency, because the sample corpus fuses to fewer than 20
  chunks. The cost is per-pair, so the cap only matters on a larger corpus,
  where it remains the right bound.
- **The bulkhead divides the machine.** ONNX Runtime parallelises one forward
  pass across cores, so `concurrency` and the intra-op thread count trade
  against each other. Sizing the bulkhead at half the core count left two
  threads per inference and made a single rerank 2.9x slower for no quality
  gain. `concurrency: 2` — few, wide inferences — is the right shape for work
  that sits on the request path.

Reranking is **on by default**. It costs +230 ms on a path whose downstream LLM
call takes seconds, and buys +12.5 points of recall@1. `CORPUS_RERANK_ENABLED=false`
turns it off, and `/api/search` takes a per-request `rerank` flag so the two
heads can be compared against a live index without a redeploy.

## Consequences

- Retrieval quality is now demonstrable rather than asserted: the eval harness
  runs every golden case with reranking off and on and prints the delta plus a
  per-case rank-movement table.
- The build asserts **non-regression against the fusion-only head on the same
  run**, not only the absolute gates. The absolute gates were set from the
  fusion-only baseline, so a reranker that quietly made ordering worse would
  still clear them — the `max-length: 128` experiment is exactly that failure,
  and the non-regression assertion is what caught it.
- `RetrievalEvalTest` also asserts `reranker.isReady()`. Every failure path here
  degrades to fusion order, so without that assertion a build could report
  "reranking is fine" having never loaded the model.
- Reranking is fail-open by construction: a missing model at startup, an
  inference error, a timeout, or a shed request under load all fall back to
  fusion order. Ranking quality is a spectrum; retrieval throwing is an outage.
  `corpus_rerank_failures_total{reason}` is what makes the degradation visible.
- Inference runs on a small **platform**-thread pool, not virtual threads: it is
  CPU-bound native work that would pin a carrier for its whole duration. The
  pool uses a `SynchronousQueue`, so saturation surfaces immediately as a shed
  rerank rather than as unbounded queueing behind a busy CPU. ONNX Runtime's
  intra-op thread count is sized against that limit, since it otherwise defaults
  to every core and the concurrent sessions would fight each other.
- The image and the demo volume carry a second ~90 MB ONNX model. It shares the
  embedding model's cache directory, so the existing CI cache, container volume,
  and Fly volume all cover it without new configuration.
- `onnxruntime` and `ai.djl.huggingface:tokenizers` are now declared explicitly.
  They resolve transitively through the Spring AI transformers starter today, and
  a transitive drop would otherwise surface only as a runtime failure.
