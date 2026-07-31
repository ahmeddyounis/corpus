# ADR 0004 — Custom token-window chunker on jtokkit

**Status:** accepted

## Context

The spec calls for token-aware chunking with overlap (512/64 by default,
configurable). Spring AI ships `TokenTextSplitter`, but its parameters
(chunk size, minimum chunk chars, max chunks) cannot express a fixed overlap
between consecutive windows.

## Decision

A ~40-line `TokenChunker`: encode the document with jtokkit's `cl100k_base`
encoding, emit sliding windows of `corpus.chunk.size` tokens advancing by
`size − overlap`, decode each window back to text, and skip blank windows.
`CORPUS_CHUNK_SIZE` / `CORPUS_CHUNK_OVERLAP` bind directly; construction
validates `0 ≤ overlap < size`.

## Consequences

- Deterministic, precisely configurable chunk geometry; unit tests assert
  window sizes and overlap content exactly.
- Overlap heals sentence-boundary cuts at ~14% storage duplication for the
  defaults.
- Token boundaries can split multi-byte characters in pathological cases;
  decode of complete windows keeps this harmless in practice.
- Structural/semantic chunking remains possible as an alternative strategy —
  and the eval harness exists precisely to judge such a change by recall@5
  and MRR rather than taste.
