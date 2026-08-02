# ADR 0012 — Per-user semantic response cache on pgvector

**Status:** accepted

## Context

The embedding cache (ADR 0011) removes the cheapest call in the pipeline. The
expensive one is the LLM: seconds of latency and cents of spend per answer. Two
users of a demo asking the same obvious question about the same corpus each pay
for it in full.

Caching a *generated answer* is riskier than caching an embedding, in a way
worth being precise about. An embedding is a pure function of its input, so a
hit is indistinguishable from a miss. An answer is derived from one user's
documents, quotes them, and is only valid for the corpus and conversation it was
produced in — so a cache with a loose notion of "same question" does not degrade
performance, it returns wrong answers that look right.

## Decision

A per-user cache in the existing pgvector store, with four independent guards.

### 1. Isolation is structural, not conventional

The row has a foreign key to `users` and cascades on delete, and every statement
in `ResponseCacheDao` carries `user_id` in its predicate. That is not defence in
depth over an application-level check — it is the only check, placed where a
future caller cannot forget it. `anotherUserNeverSeesIt` asserts the property
directly.

### 2. The threshold is measured, not chosen

The two failure modes pull in opposite directions: too low answers one question
with another's answer, too high never hits and the cache is dead weight.
`SemanticCacheEvalTest` scores all 461 cross-question pairs in the golden set
that concern different documents, and a set of hand-written paraphrases:

| | similarity |
|---|---|
| Closest unrelated pair (`corpus-chunk-defaults-vs-budget` vs `corpus-phase-timers`) | 0.547 |
| **Configured threshold** | **0.720** |
| Weakest paraphrase ("What is the chunking strategy used before embedding?") | 0.761 |

The initial guess was 0.95. It would have made the cache functionally dead — no
genuine paraphrase scores that high with `all-MiniLM-L6-v2` — while looking
perfectly reasonable in a config file. The margins are asymmetric on purpose:
0.173 of headroom on the false-hit side, where being wrong is a correctness bug,
and 0.041 on the false-miss side, where being wrong just costs an LLM call. The
test asserts a minimum margin rather than mere ordering, so an embedding model
that compresses the similarity scale fails the build instead of production.

Note the scale is model-specific, which is another reason `model_key` namespaces
every entry.

### 3. Invalidation is a stamp, not a sweep

`corpus_version` is a monotonic per-user counter bumped whenever a document
reaches READY or is deleted. Entries carry the version they were computed under,
so a corpus change makes every prior answer unreachable at once without touching
a row — and an entry written *concurrently with* an ingest is born stale and
simply never matches. That race needs no coordination, which is the point.

### 4. Only self-contained questions are cached

`first-turn-only` defaults to true. "What about the second one?" means nothing
without the turns before it, so a cached answer to it would be wrong no matter
how closely the wording matched. Opening turns are self-contained by
construction, and the MCP `ask_documents` tool is always one.

### No ANN index, deliberately

pgvector applies an approximate index *before* the `user_id` filter, so a
filtered HNSW search silently returns fewer rows than it should. For a cache
that means unpredictable misses; for a bug it means a lookup that quietly stops
being isolated. An exact scan inside one user's bounded partition — capped at
500 entries — is both faster and correct here.

## Consequences

- A repeated question costs one indexed read: the exact-match phase hashes the
  normalized question and needs no embedding at all. Only a near-miss pays for
  an embedding, and that goes through ADR 0011's cache too.
- A cache hit reports **zero tokens and zero cost**, because none were spent.
  Replaying the original turn's usage would double-count spend that never
  happened.
- **A cache hit bypasses `MessageChatMemoryAdvisor`**, which is what normally
  persists the turn. Both messages are therefore written to `ChatMemory`
  explicitly on the replay path; without that the turn would vanish and the
  *next* question would be answered without it. If that write fails, the replay
  is abandoned and the answer is generated normally — serving an answer the
  conversation will not remember is worse than paying for it again.
- The replay emits the same SSE event sequence as a generated answer, so a
  client needs no special handling; `done` carries `cached` and
  `cacheSimilarity` for anyone who wants it.
- Every failure path falls through to generating normally, and the cache write
  happens *after* the client has its answer, so caching can never delay or fail
  a request that already succeeded.
- `corpus_response_cache_total{result}` separates `hit-exact` from
  `hit-semantic`, which is what shows whether the threshold is earning its keep.
- `CORPUS_RESPONSE_CACHE_ENABLED=false` disables it entirely.
