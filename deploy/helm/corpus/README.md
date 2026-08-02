# Corpus Helm chart

```bash
helm install corpus ./deploy/helm/corpus \
  --set profile=cloud \
  --set secrets.SPRING_DATASOURCE_URL="jdbc:postgresql://db:5432/corpus" \
  --set secrets.SPRING_DATASOURCE_USERNAME=corpus \
  --set secrets.SPRING_DATASOURCE_PASSWORD=... \
  --set secrets.CORPUS_JWT_SECRET=... \
  --set secrets.ANTHROPIC_API_KEY=...
```

For anything beyond a trial, point `existingSecret` at a Secret you manage out of
band (sealed-secrets, External Secrets, SOPS) rather than passing credentials on
the command line, where they land in shell history and in the Helm release.

```bash
helm test corpus     # readiness -> mint a token -> list documents
```

## Prerequisites

- PostgreSQL 17 with the `pgvector` extension. The chart deliberately does not
  ship a database subchart: a stateful dependency that installs and uninstalls
  with the application is a good way to lose data, and every managed Postgres
  worth using already offers pgvector.
- Flyway runs on startup and owns all DDL, so the database needs to exist and be
  reachable — nothing else.
- `serviceMonitor` and `prometheusRule` need the Prometheus Operator CRDs.

## Values worth understanding before you change them

Several defaults are safe *because* of a decision elsewhere. These are the ones
where changing one thing without the other reintroduces a fixed bug.

| Value | Default | Why |
|---|---|---|
| `replicaCount` | `2` | Safe **because** MCP is served statelessly in deployed profiles (ADR 0009). Revert that and this must go back to `1` — there are no sticky sessions here. |
| `updateStrategy.rollingUpdate.maxUnavailable` | `0` | A rollout never drops below the desired count. This is also the exact scenario that used to corrupt ingestion before sweeps became ownership-scoped (ADR 0008). |
| `terminationGracePeriodSeconds` | `60` | Must stay above `spring.lifecycle.timeout-per-shutdown-phase` (45s), which is above the ingestion drain (30s). Lower it and SIGKILL lands mid-drain. |
| `preStopSleepSeconds` | `5` | Endpoint removal is eventually consistent; without the pause a pod can stop accepting connections before proxies stop sending them. |
| `resources.limits` | memory only | No CPU limit on purpose. CFS throttling stalls JVM GC threads and virtual-thread carriers at arbitrary points, turning a p95 problem into a p99 cliff. |
| `probes.*` | health groups | Paths match the actuator groups exactly. The circuit breaker is **not** in readiness: a shared provider outage would otherwise pull every replica from rotation, including for `/api/search`, which needs no chat model. |
| `probes.startup` | 150s budget | First boot loads two ONNX models. Gating liveness behind a startup probe is what stops a slow cold start becoming a restart loop. |
| `autoscaling.enabled` | `false` | CPU is a poor proxy for load in a service that spends most of a request blocked on a provider socket. The right signal is in-flight requests or phase p95 via prometheus-adapter/KEDA; a CPU HPA would scale on the wrong thing. |
| `volumes.onnxCache.persistent` | `false` | `emptyDir` re-downloads ~120 MB of models per pod start. A PVC keeps them but needs `ReadWriteMany` — `ReadWriteOnce` leaves every replica but one Pending. |

## Deployment constraints that are not enforced by the chart

- **`replicaCount × CORPUS_DB_POOL_SIZE` must fit under the Postgres connection
  cap.** Scaling out silently violates this; `CorpusDbPoolSaturated` is the
  alert that catches it after the fact.
- **Rate limiting defaults to the Postgres backend** (`CORPUS_RATE_LIMIT_BACKEND`)
  so budgets are shared across replicas. On the in-memory backend, N replicas
  means N times the intended limit.
- **`/tmp` must be executable.** ONNX Runtime extracts its native library there
  and `System.load()`s it; a `noexec` mount fails in a way that looks like a
  broken image. The chart's `emptyDir` is exec by default — do not "harden" it
  to `noexec`.

## Alert rules

`prometheusRule.enabled=true` renders [`files/alerts.yml`](files/alerts.yml) —
the same file `docker-compose` mounts into Prometheus, so both environments
alert on identical expressions and CI's `promtool check rules` covers this
manifest too. Each rule's `runbook_url` points at a section of
[`docs/runbook.md`](../../../docs/runbook.md).
