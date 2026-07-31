# ADR 0002 — PostgreSQL + pgvector as the single datastore, Flyway owning all DDL

**Status:** accepted

## Context

Corpus needs relational data (users, documents, conversations, chat memory)
and vector search. Dedicated vector databases (Qdrant, Weaviate, Milvus) win
on ANN performance at large scale but add an operational component and a
consistency boundary between a document row and its chunks.

## Decision

One PostgreSQL 17 instance with the pgvector extension. Chunks live in the
Spring AI `vector_store` table (UUID id, text content, JSONB metadata,
`vector(N)` embedding) extended with a generated `content_tsv` column for
full-text search. All DDL — including the vector store table, HNSW index
(`m=16, ef_construction=64`), and metadata indexes — is created by Flyway
migrations; `spring.ai.vectorstore.pgvector.initialize-schema` stays `false`.
Writes, deletes, and vector reads go through Spring AI's `VectorStore`
abstraction; the only pgvector-specific SQL is confined to the full-text DAO
and the migrations.

The embedding dimension is a Flyway placeholder fed from
`corpus.embedding.dimension`, and a startup guard compares the configured
dimension with the actual column, failing fast with wipe instructions after
an embedding-model switch.

## Consequences

- Transactional consistency between documents and chunks; ordinary Postgres
  backups; one container in compose.
- Deterministic schema from migrations alone — no autoconfigured DDL drift.
- RRF fusion is a same-table key merge because both search legs share chunk
  UUIDs.
- At very large corpus sizes a dedicated ANN engine would win; the VectorStore
  abstraction contains the blast radius of such a migration to configuration
  plus one DAO.
- Switching embedding models requires re-ingestion (dimension is part of the
  schema); the guard turns silent corruption into a clear startup error.
