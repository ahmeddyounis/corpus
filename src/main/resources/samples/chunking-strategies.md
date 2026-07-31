# Chunking strategies for document retrieval

Chunking decides what a retrieval system can find. Embed whole documents and
queries match only their dominant theme; slice text into fragments and no
fragment carries enough context to answer anything. Corpus takes a
deliberately simple, measurable position: token-based sliding windows with
overlap.

## Token windows, not character windows

Corpus chunks by tokens using the `cl100k_base` byte-pair encoding — the same
tokenizer family used by many embedding and chat models. The default window is
512 tokens with a 64-token overlap, both configurable through
`CORPUS_CHUNK_SIZE` and `CORPUS_CHUNK_OVERLAP`. Counting tokens rather than
characters keeps chunks aligned with how models actually consume text: 512
tokens is roughly 380 English words regardless of whether the text is dense
legal prose or airy markdown.

The window advances by `size − overlap` tokens, so consecutive chunks share a
64-token seam. Overlap exists to heal boundary wounds: a sentence that
straddles a cut appears whole in the next window, and a fact split mid-clause
still has one chunk where it survives intact. The cost is modest index
inflation — with 512/64, about fourteen percent of stored tokens are
duplicates.

## Why not semantic or structural chunking

Structural chunking (split on headings, paragraphs, or sentences) produces
variable-length chunks that read naturally but embed inconsistently: a
three-word heading and a thousand-word section become equally weighted
vectors. Semantic chunking (split where embedding similarity between adjacent
sentences drops) adapts to topic shifts but adds an embedding pass per
document, is sensitive to its own thresholds, and makes retrieval behavior
harder to reason about. Fixed token windows are predictable, cheap, and — the
deciding argument — easy to evaluate: change the chunker, rerun the retrieval
evals, and compare recall@5 and MRR before and after.

## Chunk metadata and provenance

Each chunk records its zero-based `chunk_index` alongside the owning
`document_id` and `filename`. Citations in chat answers point at
`filename#chunk`, so a reader can trace any claim to the exact window it came
from. Chunk identity is stable for the lifetime of a document: re-uploading a
file deletes the old chunk rows and writes new ones.

## Sizing guidance

Small windows (128–256 tokens) suit corpora of short factoids and raise
precision: more of each retrieved chunk is actually about the query. Large
windows (768–1024) suit narrative documents where answers need surrounding
argument, at the cost of diluting the embedding and spending more of the
prompt budget per retrieved chunk. The 512-token default is a defensible
middle for mixed business documents — contracts, runbooks, reports. Whatever
the choice, it should be validated against a golden set rather than argued
about: retrieval quality is measurable, and the eval harness exists precisely
so chunking changes are compared with numbers instead of vibes.

## Failure modes to watch

Tables shredded by parsing chunk badly — a row's cells scatter across
windows. Documents with heavy boilerplate (headers repeated on every page)
produce near-duplicate chunks that crowd the top-k; deduplication or
boilerplate stripping at parse time is the fix. And extremely short documents
produce a single tiny chunk whose embedding is dominated by the title — fine
for lookup, useless for synthesis.
