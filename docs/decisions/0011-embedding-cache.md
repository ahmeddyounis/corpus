# ADR 0011 — Content-addressed embedding cache, shared across users

**Status:** accepted

## Context

Every retrieval embeds the query, and every ingestion embeds each chunk. Under
the `local` and `keyless` profiles that is CPU; under `cloud` with OpenAI
embeddings it is a billed API call with network latency on the request path.
Nothing was cached, so asking the same question twice cost two embeddings.

## Decision

A two-tier, content-addressed cache in front of whichever `EmbeddingModel` the
active profile auto-configured: a bounded in-process LRU per replica, and a
Postgres table shared by all of them.

The cache key is `sha256(text)` under a namespace of
`provider:model:dimension`.

### Why the namespace is load-bearing

The way an embedding cache goes wrong is by serving a vector from a model that
is no longer in use — a vector that lives in a different space than everything
it will be compared against, producing retrieval that is quietly nonsense rather
than obviously broken. Putting every input that determines the vector into the
key makes that **structurally impossible**: after a model or dimension change
the old entries simply cannot be addressed, so the cache misses and refills.
This is the alternative to a convention someone has to remember to follow during
a model migration.

### Why it is safe to share across users

Corpus partitions everything by user, enforced at the storage layer. This cache
deliberately crosses that partition, and it is worth being explicit about why
that is sound here and nowhere else:

- An embedding is a **pure function** of `(text, provider, model, dimension)`.
  Two users embedding identical text get identical vectors, so sharing changes
  no result.
- A row holds a **SHA-256 and a vector**. The source text is never stored, and
  the table is only ever read by exact hash — there is no query that returns
  content, and no way to enumerate what other users have uploaded.
- A hit is **not observable** to the caller: results are rebuilt in request
  order with per-position indexes, so a caller cannot tell which of its inputs
  were already known.

Document text, chunks, conversations, and search results remain strictly
per-user. This is the one derived, non-returnable artifact where sharing is
both safe and useful, and the integration test asserts the table has no
plaintext column to leak.

### Why a `BeanPostProcessor`

Nothing in this codebase calls the embedding model directly — `PgVectorStore`
embeds internally on both `add` and `similaritySearch` — so the cache has to
wrap the bean itself. A `@Primary` `EmbeddingModel` could not work: the wrapper
needs the real model injected, and the only `EmbeddingModel` available to inject
would be the wrapper. Post-processing is the seam that wraps a bean this
application does not own.

The post-processor resolves its collaborators lazily from the bean factory
rather than injecting them, because a post-processor that injects beans forces
those beans to initialise early and out of the reach of every other
post-processor in the context.

## Consequences

- Repeated queries and re-uploaded content cost no embedding at all;
  `corpus_embedding_cache_total{result,tier}` shows the hit ratio per tier and
  makes the saving visible rather than assumed.
- Under `cloud` with OpenAI embeddings this is a direct cost reduction; under
  `local`/`keyless` it is a latency reduction.
- Both tiers are optimisations, so every failure path — a failed lookup, a
  failed write, a short response from the delegate — falls through to a real
  embedding call. A cache outage degrades speed, never correctness.
- The shared tier is bounded per namespace and trimmed by last use, checked
  every quarter-capacity rather than per write so the common path stays one
  insert.
- Writes use `ON CONFLICT DO NOTHING`. Two replicas embedding the same text
  concurrently is wasteful but correct — they computed the same vector — so the
  conflict only needs absorbing, not serialising.
- Duplicate texts inside one batch are embedded once, which matters for
  ingestion of documents with repeated boilerplate.
- `CORPUS_EMBEDDING_CACHE_ENABLED=false` removes the wrapper entirely.
