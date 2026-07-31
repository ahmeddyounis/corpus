# Corpus architecture: a modular monolith for retrieval-augmented generation

Corpus is deliberately built as a modular monolith: one deployable Spring Boot
application with strict package boundaries instead of a fleet of microservices.
The six top-level modules are `ingestion`, `retrieval`, `chat`, `mcp`, `ops`,
and `security`. Each module owns its controllers, services, and persistence
concerns, and cross-module calls go through public service interfaces — never
through another module's repositories.

## Why one deployable

A retrieval-augmented generation service at this scale gains nothing from
network boundaries between its parts. Splitting ingestion and retrieval into
separate services would add serialization overhead, deployment orchestration,
and distributed-failure modes to a system whose hot path is already dominated
by model inference latency. The monolith keeps the demo runnable with a single
`docker compose up`, keeps stack traces whole, and keeps refactoring cheap
while the domain is still settling.

The boundaries still matter. Because the seams between `ingestion`,
`retrieval`, and `chat` are explicit service interfaces, any one of them could
be extracted into its own process later without rewriting callers. The rule of
thumb: a module may depend on another module's service API and on shared
configuration, but never on its tables or internal classes.

## The request path

An authenticated chat request flows through four modules. The `security`
module validates the bearer token and resolves the user id. The `chat` module
orchestrates: it calls `retrieval` to run hybrid search scoped to that user,
assembles a prompt with numbered context chunks, and streams the model
response back over Server-Sent Events. The `ops` module observes everything,
recording per-phase latency timers and token counters. Ingestion runs on a
separate asynchronous path: uploads return HTTP 202 immediately and the
parse-chunk-embed pipeline executes on a virtual-thread executor.

## Virtual threads over reactive

Corpus runs on Java virtual threads (`spring.threads.virtual.enabled=true`)
rather than a reactive stack. RAG workloads are I/O-bound fan-out: two search
legs run in parallel, model calls block for seconds, and SSE streams trickle
tokens. Virtual threads give that concurrency with ordinary blocking code —
straightforward stack traces, debuggable breakpoints, and no operator-chain
ceremony. The only Reactor usage in the codebase is consuming the streaming
`Flux` that Spring AI returns, which the chat service iterates blockingly on a
virtual thread.

## Data ownership

PostgreSQL is the single datastore. Relational tables (`users`, `documents`,
`conversations`, chat memory) live beside the `vector_store` table that holds
chunk text, JSONB metadata, and pgvector embeddings. Flyway owns every piece
of DDL, including the vector store schema, so a fresh database is always
reproducible from migrations alone. Transactional consistency between a
document row and its chunks is trivial because both live in the same database
— there is no dual-write problem between a relational store and a separate
vector engine.

## Where it would split

If Corpus outgrew a single process, the first seam to cut is ingestion: it is
already asynchronous, communicates through the database, and has bursty
resource demands (parsing large PDFs). The second seam is retrieval, which
could become a stateless search service in front of the same PostgreSQL. Chat
would stay closest to the API edge because it owns SSE connections and
conversation state. The MCP surface is a thin adapter over retrieval and chat
and would move with whichever host owns those services.
