# ADR 0003 — Hybrid retrieval with Reciprocal Rank Fusion (k=60)

**Status:** accepted

## Context

Pure vector search misses exact identifiers, error codes, and rare terms
(embeddings blur them); pure keyword search misses paraphrases. Corpus serves
questions of both kinds. More elaborate fusion methods (learned rankers,
score normalization schemes, cross-encoder reranking) exist.

## Decision

Run both legs in parallel on virtual threads — PostgreSQL full-text search
(`websearch_to_tsquery` + `ts_rank_cd` over the generated tsvector) and
cosine similarity over pgvector — each contributing `candidateK=20` results,
then fuse with Reciprocal Rank Fusion: `score(d) = Σ 1/(k + rank_i(d))` with
1-based ranks and `k=60` (`CORPUS_RRF_K`). Top `CORPUS_RETRIEVAL_TOP_K`
fused chunks reach the prompt.

## Consequences

- RRF needs no score normalization (ranks only), has one parameter, and
  rewards cross-leg agreement — measurably better recall on the golden set
  than either leg alone (the keyword and paraphrase eval cases each fail
  without their leg).
- Both per-leg scores are preserved in API responses for debuggability.
- A cross-encoder reranking stage after fusion is the natural next quality
  step (roadmap) and slots in cleanly after the fuser.
- Full-text ranking is English-configured (`to_tsvector('english', ...)`);
  multilingual corpora would need per-language configuration.
