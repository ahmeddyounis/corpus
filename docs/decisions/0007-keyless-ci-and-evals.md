# ADR 0007 — Keyless CI: ONNX embeddings + stubbed chat; judge evals nightly

**Status:** accepted

## Context

LLM apps regress silently — prompt tweaks, dependency bumps, and chunking
changes shift behavior without failing any conventional test. But putting a
paid model on the PR path makes CI slow, flaky, secret-dependent, and
expensive; and forks can't run it.

## Decision

Two eval tiers with different determinism/cost trade-offs:

1. **Every PR (keyless, deterministic):** integration tests and retrieval
   evals run against real Postgres+pgvector (Testcontainers) with the
   in-process ONNX `all-MiniLM-L6-v2` embedding model
   (`spring.ai.model.embedding=transformers`, 384-dim) and a deterministic
   `StubChatModel` (`@Primary` in tests) for chat plumbing. The golden set
   gates recall@3 ≥ 0.82, recall@5 ≥ 0.88, MRR ≥ 0.74 and nDCG@5 ≥ 0.76;
   JaCoCo gates 80% line coverage on core packages.

   Gate values are set from a measured run minus a stated 0.05 margin, and the
   corpus includes distractor documents so the metrics do not saturate — a
   harness that always scores 1.000 cannot demonstrate an improvement or catch
   a regression.

   Every case runs twice, with reranking off and on ([ADR 0010](0010-cross-encoder-reranking.md)),
   and the build additionally asserts non-regression against the fusion-only head
   on the same run. The absolute gates above were measured from that head, so a
   reranker that made ordering worse would still clear them.
2. **Nightly (real model):** `./gradlew nightlyEval` answers the golden set
   with a real Anthropic model (the stub is disabled under the `nightly`
   profile) and an LLM judge scores faithfulness and relevance, both gated
   at 0.80, with the report published to the workflow summary.

## Consequences

- PRs stay fast, free, and fork-runnable; retrieval regressions fail with a
  per-case table naming the regressed questions.
- Model-behavior regressions surface within a day rather than per-PR, an
  explicit trade of latency for stability and cost.
- The stub echoes citation markers, so SSE framing, memory, and citation
  plumbing are asserted exactly without a model in the loop.
- Judge scores carry inherent variance; gates are set with margin and the
  nightly report keeps the trend visible rather than a single point.
