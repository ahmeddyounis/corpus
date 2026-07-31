# The Model Context Protocol: how AI clients call Corpus as a tool

The Model Context Protocol (MCP) is an open standard that lets AI
applications — Claude Desktop, IDE agents, other LLM apps — discover and call
external capabilities as tools. Corpus implements the server side of the
protocol, so its document search and question answering become native tools
inside any MCP-compatible client.

## Protocol shape

MCP is JSON-RPC 2.0 over a transport. A session begins with an `initialize`
request in which client and server exchange protocol versions and
capabilities; the client then sends a `notifications/initialized`
notification and the session is live. Tool discovery uses `tools/list`, which
returns each tool's name, natural-language description, and a JSON Schema for
its input arguments. Invocation uses `tools/call` with the tool name and an
arguments object; results carry structured content plus an `isError` flag so
models can react to failures.

## Streamable HTTP transport

Corpus serves MCP over the streamable HTTP transport at a single `/mcp`
endpoint. Clients POST JSON-RPC messages; responses arrive either as plain
JSON or as a short server-sent-events stream, which lets the server push
progress notifications for long-running calls. The server assigns a session
on initialize and the client echoes it back in the `Mcp-Session-Id` header on
every subsequent request. A GET on the same endpoint can open a listening
stream for server-initiated messages, and DELETE ends the session. Compared
with the older SSE transport (separate endpoints for events and messages),
streamable HTTP behaves like ordinary HTTP: it works through load balancers,
proxies, and API gateways without sticky-session gymnastics.

## The Corpus tool surface

Corpus exposes three tools. `search_documents` runs the same hybrid retrieval
as the REST API — keyword full-text plus vector similarity, fused with
Reciprocal Rank Fusion — and returns ranked chunks with filenames, chunk
indexes, and scores. `ask_documents` runs the full RAG pipeline and returns a
synthesized answer with citations. `list_documents` reports the user's
uploaded files with ingestion status and chunk counts. The descriptions are
written for a model audience: they say when to prefer search over ask, which
is what lets an agent compose them sensibly.

## Identity and scoping

Every tool call executes as a specific Corpus user, because retrieval is
scoped per user at the storage layer. When a bearer JWT accompanies the HTTP
request, the tool call acts as that token's user. In the local profile,
anonymous MCP calls are mapped to the demo account so that connecting Claude
Desktop to a fresh instance requires zero configuration. In cloud profiles
the anonymous mapping is disabled and a token is mandatory — the MCP surface
never widens access beyond what the REST API would allow the same caller.

## Why MCP and REST both

REST serves humans and frontends; MCP serves agents. The distinction is
audience, not capability: both surfaces call the same services underneath.
Implementing MCP is cheap precisely because the modular monolith keeps
retrieval and chat behind clean interfaces — the entire MCP module is a thin
adapter that annotates three methods as tools and resolves the acting user.
