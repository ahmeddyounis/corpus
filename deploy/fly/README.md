# Fly.io demo

One-time setup. After this, [`deploy-demo.yml`](../../.github/workflows/deploy-demo.yml)
deploys on every successful publish and rolls back automatically if the
post-deploy smoke test fails.

```bash
flyctl launch --no-deploy --config deploy/fly/fly.toml --name corpus-demo
flyctl volumes create corpus_data --size 3 --region iad
```

Managed Postgres with pgvector, attached — Flyway creates the extension on first
startup, so nothing else is needed:

```bash
flyctl mpg create --name corpus-demo-db --region iad
flyctl mpg attach corpus-demo-db --app corpus-demo
```

Secrets. The JWT secret is the only one required; deliberately no model API key,
which is what makes the demo safe to leave publicly reachable:

```bash
flyctl secrets set --app corpus-demo CORPUS_JWT_SECRET="$(openssl rand -base64 48)"
```

Then add `FLY_API_TOKEN` (`flyctl tokens create deploy -x 999999h`) as a
repository secret so the workflow can deploy.

## Why the demo runs keyless

Anyone with the URL can use it, so the question is what damage a stranger can do.
Under the `keyless` profile the answer is bounded by construction rather than by
a limit someone has to remember to set:

- **No model API key exists**, so no one can spend money. `/api/chat` returns a
  self-documenting 503 explaining how to enable chat locally.
- Everything that makes the project interesting still works: upload, chunking,
  in-process ONNX embeddings, hybrid retrieval, **cross-encoder reranking**,
  MCP `search_documents`/`list_documents`, Prometheus metrics, Swagger UI.
- Rate limiting is keyed on `Fly-Client-IP`, which Fly's proxy sets and a client
  cannot forge — unlike `X-Forwarded-For`, which is trivially spoofable if
  trusted from an untrusted peer.
- Budgets are shared across machines through Postgres, so scaling out does not
  multiply the limit.

## `/mcp` is publicly reachable, and that is deliberate

A reviewer can paste `https://corpus-demo.fly.dev/mcp` straight into Claude
Desktop. Four facts make that safe, and they are worth stating together rather
than trusting one of them:

1. Anonymous access is **read-only** — uploads require a JWT.
2. It is throttled per real client IP.
3. `ask_documents` is unavailable under `keyless`, so no tool call can spend
   money.
4. The corpus is the bundled sample documents, which are public in this repo.

See [`docs/mcp-setup.md`](../../docs/mcp-setup.md) for the client configuration.

## Operating it

```bash
flyctl logs --config deploy/fly/fly.toml          # ECS JSON, one line per event
flyctl status --config deploy/fly/fly.toml
flyctl releases rollback --config deploy/fly/fly.toml
flyctl ssh console --config deploy/fly/fly.toml -C "du -sh /data/onnx"
```

`kill_timeout` is 60s, above the 45s `spring.lifecycle.timeout-per-shutdown-phase`,
which is above the 30s ingestion drain. Lowering it lands SIGKILL mid-drain and
loses in-flight uploads.
