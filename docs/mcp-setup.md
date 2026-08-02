# Connecting Corpus to Claude Desktop (and other MCP clients)

Corpus exposes an MCP server over **streamable HTTP** at `http://localhost:8080/mcp`
with three tools:

| Tool | What it does |
|---|---|
| `search_documents` | Hybrid keyword+vector retrieval; returns ranked chunks with provenance and scores |
| `ask_documents` | Full RAG answer with citations |
| `list_documents` | Uploaded documents with ingestion status and chunk counts |

## 0. Or skip setup entirely: the hosted demo

`https://corpus-demo.fly.dev/mcp` is publicly reachable and needs no
credentials, so you can paste it into a client and try the tools in about a
minute. Four facts make leaving it open safe, and they are worth stating
together rather than relying on any one of them:

1. Anonymous access is **read-only** — uploading requires a JWT.
2. It is throttled per real client IP, keyed on `Fly-Client-IP`, which Fly's
   proxy sets and a client cannot forge.
3. `ask_documents` is **unavailable** there: the demo runs the `keyless` profile
   with no model API key, so no tool call can spend money. `search_documents`
   and `list_documents` work fully, reranking included.
4. The corpus is the bundled sample documents, which are already public in this
   repository.

Substitute that URL for `http://localhost:8080/mcp` in the configuration below.

## 1. Start Corpus

```bash
docker compose up --build
```

Wait for the app to report healthy. In the `local` profile the demo user's
corpus is pre-seeded with the sample documents, and **anonymous MCP calls act
as the demo user** (`corpus.mcp.anonymous-user=demo`) — so Claude Desktop
needs zero credentials to try it.

## 2. Add Corpus to Claude Desktop

Claude Desktop configures MCP servers in `claude_desktop_config.json`
(macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`).
The [`mcp-remote`](https://www.npmjs.com/package/mcp-remote) proxy bridges
Desktop to a streamable-HTTP server:

```json
{
  "mcpServers": {
    "corpus": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:8080/mcp"]
    }
  }
}
```

Restart Claude Desktop; the three Corpus tools appear in the tools menu. Ask
something like *"Search my documents for how HNSW parameters are tuned"* and
watch it call `search_documents`.

### Authenticated connection (cloud profile, or acting as a specific user)

Mint a token, then pass it as a header. Use an env-var indirection — Claude
Desktop splits `args` on spaces, so `Bearer <token>` must not appear inline:

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo"}' | jq -r .token)
```

```json
{
  "mcpServers": {
    "corpus": {
      "command": "npx",
      "args": [
        "-y", "mcp-remote", "http://localhost:8080/mcp",
        "--header", "Authorization:${AUTH_HEADER}"
      ],
      "env": {
        "AUTH_HEADER": "Bearer <paste-token-here>"
      }
    }
  }
}
```

In cloud profiles `corpus.mcp.anonymous-user` is unset, so the header is
required — MCP access is always exactly as wide as REST access for the same
caller, never wider.

## 3. Debugging with MCP Inspector

```bash
npx @modelcontextprotocol/inspector
```

Choose transport **Streamable HTTP** and URL `http://localhost:8080/mcp`, then
list tools and invoke them interactively. Or go fully raw:

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}' -i
```

The response carries an `Mcp-Session-Id` header — echo it back on subsequent
`tools/list` / `tools/call` requests.

## Troubleshooting

- **404 at /mcp** — the server only registers the streamable transport when
  `spring.ai.mcp.server.protocol=STREAMABLE` (set in `application.yml`; don't
  remove it).
- **Session errors** — every request after `initialize` must carry the
  `Mcp-Session-Id` header from the initialize response. `mcp-remote` handles
  this automatically.
- **401 on tool calls** — token expired (24h TTL): mint a new one and update
  `AUTH_HEADER`.
- **Empty search results** — the acting user has no READY documents: check
  `list_documents`, or upload via `POST /api/documents` with that user's
  token.
- **Tools missing in Claude Desktop** — fully quit and restart Desktop after
  editing the config; check its MCP logs for `mcp-remote` connection errors.
