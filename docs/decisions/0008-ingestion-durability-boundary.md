# ADR 0008 — The ingestion durability boundary

**Status:** accepted

## Context

`POST /api/documents` returns `202 Accepted` immediately and processes the upload
asynchronously. The uploaded bytes live only in the submitted task's closure, on
the heap of the instance that accepted the request. Nothing durable holds them:
the `documents` table stores metadata, and the only persisted artifact is the
derived chunks written at the end of the pipeline.

That means work cannot migrate between instances, and a process that dies mid-
ingestion cannot resume. The question is how much machinery is proportionate.

## Decision

**Make the drain finish the work, rather than making the payload durable.**

- Executors are `SimpleAsyncTaskExecutor` with an explicit
  `taskTerminationTimeout` (ingestion 30s), so SIGTERM drains in-flight ingestion
  instead of blocking forever. The previous `ExecutorService.close()` was
  `shutdown()` + `awaitTermination(Long.MAX_VALUE)` — it would outlast any
  container grace period and get SIGKILLed mid-embedding, which is the opposite
  of graceful.
- The shutdown budget is a single chain, and every layer must respect it:
  ingestion 30s / chat 20s → `spring.lifecycle.timeout-per-shutdown-phase: 45s`
  → Kubernetes `terminationGracePeriodSeconds: 60` / Fly `kill_timeout = 60s`.
- Ingestion is bulkheaded (`corpus.async.ingestion-concurrency`, default 4) and
  sheds load past the cap rather than queueing without bound, so the drain has a
  bounded amount of work to finish.
- What genuinely cannot finish is failed by its own instance on shutdown, and by
  the owner-scoped startup sweep after a hard kill (ADR: see
  `StaleIngestionSweeper`), with its partial chunks removed and an actionable
  error message.

With `maxUnavailable: 0` and a bounded drain, the overwhelming majority of
rolling-deploy ingestions simply complete.

## Consequences

**Accepted limitation, stated plainly:** a hard SIGKILL, an OOM kill, or node
loss between the `202` and completion loses that upload permanently. The user
sees `FAILED` with "please re-upload" and must re-upload. There is no data
*corruption* — the compare-and-set transitions and chunk cleanup guarantee that —
only lost work.

**Upgrade path**, if that becomes unacceptable:
1. Persist the payload (a `document_payloads` table keyed by document id, or
   object storage with the key in `documents`).
2. Status transitions become resumable, since any instance can re-read the bytes.
3. The startup sweeper changes from *fail* to *requeue*, and the shutdown sweeper
   is no longer needed at all.

That is a durability workstream — a table with a size budget, a retry endpoint,
and garbage collection — deliberately out of scope here. The boundary is recorded
so the omission is a decision rather than an oversight.
