# Runbook

One section per alert in [`alerts.yml`](../deploy/helm/corpus/files/alerts.yml),
reachable from the `runbook_url` on the rule itself. An alert with no documented
response is a pager that trains people to ignore it.

Each section says what fired, what it usually means, how to confirm, and how to
stop the bleeding — in that order, because "make it stop" and "find out why" are
different jobs and the first one comes first.

## First moves, whatever fired

```bash
curl -s localhost:8080/actuator/health/readiness
curl -s localhost:8080/actuator/health/liveness
curl -s localhost:8080/actuator/prometheus | grep -E '^corpus_|^resilience4j_|^hikaricp_'
```

Logs are ECS JSON in deployed profiles and carry `requestId` across the virtual-thread
hop, so one request is greppable end to end:

```bash
kubectl logs -l app.kubernetes.io/name=corpus --tail=500 | jq -r 'select(.requestId=="<id>")'
```

---

## CorpusErrorBudgetBurnFast

**Fired:** 5xx rate above 14.4x the sustainable burn, in both a 1h and a 5m window.
At this rate the whole month's budget is gone in about two days.

**Usually:** a bad deploy, the database being unreachable, or a provider returning
errors fast enough that the breaker has not yet tripped.

**Confirm** which of the three:

```bash
curl -s localhost:8080/actuator/prometheus | grep -E 'hikaricp_connections_pending|resilience4j_circuitbreaker_state'
```

**Stop the bleeding:** roll back first, diagnose after. `kubectl rollout undo
deployment/corpus`, or `flyctl releases rollback` on the demo. Rolling deploys are
safe to interrupt — ingestion ownership is scoped per instance (ADR 0008), so an
aborted rollout does not corrupt in-flight documents.

## CorpusErrorBudgetBurnSlow

**Fired:** 6x burn sustained over 6h. Not urgent, but the month's budget will be
gone in about five days.

**Usually:** a partial failure that retries paper over — one flaky replica, or an
endpoint failing for a subset of inputs.

**Confirm:** break the error rate down by URI and instance rather than looking at
the aggregate.

```promql
sum by (uri, status) (rate(http_server_requests_seconds_count{status=~"5.."}[30m]))
```

**Then:** fix forward. This does not warrant a rollback on its own.

## CorpusInstanceDown

**Fired:** a replica has not been scraped for 2 minutes.

**Usually:** the pod is restarting (OOM, failed probe) or Prometheus service
discovery is stale.

**Confirm:** `kubectl get pods -l app.kubernetes.io/name=corpus` — look at
RESTARTS and the last termination reason. `OOMKilled` means the heap outgrew
`MaxRAMPercentage=70`; the ONNX models (embedding + cross-encoder) are the usual
new consumer.

**Then:** raise the memory limit, or set `CORPUS_RERANK_MODEL_ENABLED=false` to
drop the ~90 MB cross-encoder. Retrieval degrades to fusion order, which is a
quality loss, not an outage.

## CorpusSlowFirstToken

**Fired:** p95 time-to-first-token above the 5s objective for 10 minutes.

**Usually:** provider latency. But check reranking first — it sits *ahead* of the
model call and adds ~230 ms by design:

```promql
histogram_quantile(0.95, sum by (le) (rate(corpus_retrieval_rerank_seconds_bucket[10m])))
```

If rerank p95 is far above 230 ms, the bulkhead is saturated (see
`CorpusRerankingDegraded`). If it is normal, the time is in the provider.

**Then:** nothing to do about provider latency but wait, unless a model swap
caused it. `spring.ai.{anthropic,openai}.timeout` is 90s and Spring AI retries
twice, bounding the worst case at ~540 s before the breaker intervenes.

## CorpusSlowRetrieval

**Fired:** p95 retrieval above 500 ms for 10 minutes.

**Usually:** database, not application. Retrieval issues two concurrent queries
per request.

**Confirm:**

```bash
curl -s localhost:8080/actuator/prometheus | grep -E 'hikaricp_connections_(pending|active|max)'
```

Pending connections above zero means the pool is the bottleneck, not the queries.

**Then:** if the pool is saturated see `CorpusDbPoolSaturated`. If it is not, a
`statement_timeout` of 15s is in force, so a runaway HNSW scan fails rather than
hangs — look for a missing index after a migration.

## CorpusRetrievalDegraded

**Fired:** the fleet-mean RRF top score has fallen to single-leg levels (< 0.017,
i.e. around `1/(60+1)`, meaning only one retrieval leg is finding anything).

**Usually:** not a slow system — a *broken* one. In order of likelihood: an
embedding dimension mismatch after a model change, a failed migration leaving the
FTS index missing, or simply active users whose corpus is empty.

**Confirm:**

```sql
SELECT count(*) FROM vector_store;
SELECT count(*) FROM documents WHERE status = 'READY';
SELECT indexname FROM pg_indexes WHERE tablename IN ('vector_store', 'document_chunks');
```

`DimensionGuard` fails startup on a dimension mismatch, so a *running* instance
has already ruled that out — which usually leaves the FTS side.

**Then:** if the embedding model changed, the vectors must be rebuilt; a
namespaced embedding cache (ADR 0011) means the cache will not serve the old
model's vectors, but the stored ones are still stale.

## CorpusRerankingDegraded

**Fired:** reranking is falling back to fusion order.

**This is the alert that exists because the feature fails open.** Reranking never
throws — it silently reverts to the RRF baseline — so without this the only
symptom is that quality quietly stops matching the eval numbers.

**Read the `reason` label:**

- `shed` / `timeout` — the bulkhead is saturated. `corpus.rerank.concurrency`
  defaults to 2 wide inferences; raising it makes each one narrower (ONNX intra-op
  threads are sized as `cores / concurrency`), so raise CPU before raising this.
- `load` — the model never loaded. Check the cache directory is writable and the
  volume is mounted; on the container this is `/var/cache/corpus-onnx`.
- `inference` — a genuine ONNX error. Check the logs for the graph-validation
  message; an upstream model re-export that renames a node fails fast with the
  real node names.

**Then:** `CORPUS_RERANK_ENABLED=false` makes the degradation explicit rather than
silent, which is preferable while investigating.

## CorpusRateLimit429Storm

**Fired:** more than 20% of requests are being rate limited.

**Usually one of two very different things.** Either a real traffic burst, or —
the reason this alert exists — every client is being keyed to the *same* bucket
because forwarded-header handling regressed. That turns a per-client limit into a
global one.

**Confirm:** if it is a genuine burst, the 429s spread across many client IPs. If
it is a keying regression, they collapse onto one. Check that the proxy header is
still trusted:

```bash
kubectl exec deploy/corpus -- env | grep -E 'CORPUS_REMOTE_IP_HEADER|FORWARD'
```

**Then:** for a real burst, raise `CORPUS_RATE_LIMIT_RPM` or scale out — with the
Postgres backend the budget is shared across replicas, so scaling out does *not*
multiply the limit. For a keying regression, restore
`server.forward-headers-strategy: NATIVE` and the correct header.

## CorpusCostBurn

**Fired:** estimated spend above $1/hour for 30 minutes.

**Confirm** whether it is volume or price:

```promql
sum(rate(corpus_llm_tokens_total[1h])) * 3600
sum by (model) (rate(corpus_llm_cost_estimate_usd_total[1h])) * 3600
```

Flat token throughput with rising cost means a model swap or a stale price table
(`corpus.pricing.models.*`), not more traffic.

**Then:** per-user daily quotas (ADR 0013) already cap individual spend; check
`corpus_quota_blocked_total` to see whether they are binding at all. Lower
`CORPUS_DAILY_TOKENS`, or check the cache hit ratios — a collapsed response-cache
hit rate after an upload is expected (the corpus version bumped) but a
permanently low one is not.

## CorpusIngestionBacklog

**Fired:** more than 20 documents have been PENDING or PROCESSING for 15 minutes.

**This is the production check on the stale-ingestion sweeper.** Either sustained
load beyond the ingestion bulkhead (4 concurrent by default, which sheds with a
503 rather than queueing), or documents stuck in PROCESSING that nothing is
reclaiming.

**Confirm** which:

```sql
SELECT status, owner_instance, count(*), min(claimed_at)
  FROM documents WHERE status IN ('PENDING','PROCESSING')
 GROUP BY status, owner_instance;
```

Rows with a `claimed_at` older than 15 minutes and an `owner_instance` that no
longer exists should have been swept. If they have not, the sweeper is not running
— check for startup errors.

**Then:** the sweep runs at startup and is scoped by owner and age, so restarting
a replica reclaims its own stranded rows without touching another live instance's
in-flight work.

## CorpusCircuitBreakerOpen

**Fired:** a breaker is rejecting calls without reaching the provider.

**Note what this does *not* mean:** the service is not down. The breaker is
deliberately excluded from the readiness group, so `/api/search`,
`/api/documents`, and MCP `search_documents` keep working normally — only chat
returns 503. That is a correct degraded mode.

**Confirm:**

```bash
curl -s localhost:8080/actuator/prometheus | grep resilience4j_circuitbreaker
```

**Then:** the breaker half-opens on its own. 4xx responses are in
`ignoreExceptions`, so an open breaker means genuine provider failures or
timeouts, not bad requests. If the provider is fine, look for a network path
problem from the cluster.

## CorpusDbPoolSaturated

**Fired:** Hikari pool over 90% utilised for 10 minutes.

**Usually:** the deployment constraint has been violated —
`replicas x maximumPoolSize` must fit under the managed-Postgres connection cap.
Scaling out silently breaks this.

**Confirm:**

```sql
SELECT count(*), state FROM pg_stat_activity WHERE datname = 'corpus' GROUP BY state;
SHOW max_connections;
```

`idle in transaction` rows mean a leak, not saturation —
`idle_in_transaction_session_timeout` is 30s, so they should not persist.

**Then:** lower `CORPUS_DB_POOL_SIZE` before scaling out further. Requests wait up
to the 3s `connection-timeout` and then fail, so this becomes an availability
problem quickly.
