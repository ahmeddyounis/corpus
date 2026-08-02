# Choosing between HNSW and IVFFlat in pgvector

pgvector offers two approximate-nearest-neighbour index types, and they behave
differently enough that the choice is worth making deliberately rather than by
habit. This note compares them in the abstract; it does not describe how any
particular deployment is configured.

## IVFFlat: partition then probe

IVFFlat clusters vectors into lists during index build, then searches only the
lists closest to the query. Its two knobs are `lists` (how many partitions to
create) and `probes` (how many to scan at query time). Community guidance
suggests roughly `rows / 1000` lists for tables under a million rows, and
`sqrt(rows)` beyond that, with `probes` starting near `sqrt(lists)`.

The defining characteristic is that IVFFlat requires representative data to
exist *before* the index is built: the clustering is derived from the vectors
present at build time. Build an IVFFlat index on an empty table and recall
collapses, because every later insert lands in whatever partition the initial
sample happened to create. Rebuilding after a large ingest is normal practice.

## HNSW: a navigable graph

HNSW builds a layered proximity graph instead of partitions. Its build knobs are
`m`, the number of bidirectional links retained per node, and `ef_construction`,
the size of the candidate list explored while inserting. Query time is governed
by `ef_search`.

HNSW does not need pre-existing data, so it can be created on an empty table and
stays accurate as rows arrive. That property alone often decides the choice for
systems that ingest continuously.

## The trade-offs that actually matter

| Dimension | IVFFlat | HNSW |
|---|---|---|
| Build time | Fast | Considerably slower |
| Index size | Smaller | Larger |
| Recall at equal speed | Lower | Higher |
| Needs data before build | Yes | No |
| Tolerates continuous inserts | Degrades, wants rebuilds | Stable |

IVFFlat remains attractive when the corpus is static, memory is tight, or index
build time dominates. HNSW is generally the better default for a live system,
paying memory and build cost for recall and operational simplicity.

## Memory planning

HNSW memory scales with the number of vectors, their dimensionality, and `m`.
A rough estimate is vectors × (4 bytes × dimensions + 8 bytes × m × 2). The index
should fit comfortably in `maintenance_work_mem` during build and in shared
buffers or the OS page cache during queries; when it does not, latency degrades
sharply and unpredictably as pages are fetched from disk.

## Migration notes

Switching index types does not require re-embedding — the vectors are unchanged,
only the access path differs. Drop one index and create the other. Build the new
index concurrently where downtime is unacceptable, and be aware that a
non-concurrent build takes an exclusive lock for its duration.
