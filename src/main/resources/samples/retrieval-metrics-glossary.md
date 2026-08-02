# A glossary of information-retrieval metrics

These are the standard definitions used across search and recommendation
evaluation. This note defines the metrics; it does not state any particular
system's targets or measured values.

## Recall@k

The proportion of evaluation cases for which at least one relevant item appears
in the top *k* results. It is binary per case: the relevant item is either in
the window or it is not, and its position inside the window is irrelevant.
Recall@k answers "did we surface it at all", which makes it the right metric
when a human or a downstream model will scan the whole window anyway.

## Precision@k

The proportion of the returned *k* items that are relevant. Where recall asks
whether anything useful appeared, precision asks how much of what was returned
was useful. Precision@k falls as *k* grows for any fixed set of relevant items,
so comparing precision across different *k* values is meaningless.

## Mean Reciprocal Rank

For each case, take the reciprocal of the rank of the first relevant result —
1.0 at position one, 0.5 at position two, 0.333 at position three — then average
across cases. MRR rewards putting the answer first and is the natural metric
when a system will act on the top result alone. A case with no relevant result
contributes zero.

## Normalised Discounted Cumulative Gain

nDCG generalises the above to graded relevance and to multiple relevant items.
Each result contributes gain discounted logarithmically by its position, so
items further down count less; the sum is normalised by the ideal ordering's
score, producing a value in [0, 1]. With binary relevance the gain is 1 or 0 and
the discount is `1 / log2(position + 1)`.

nDCG is the most informative of the four when several results matter and their
ordering matters, which is why it is standard in ranking evaluation. It is also
the least intuitive to read at a glance.

## Choosing among them

Report more than one. Recall alone hides a system that finds the answer but
buries it; MRR alone hides a system that ranks its one hit perfectly while
missing everything else. A common pairing is recall at the window size actually
used plus nDCG for ordering quality, with precision reported when result-set
noise is a concern.

## Statistical caution

Evaluation sets of a few dozen cases produce metric estimates with wide
confidence intervals: a difference of one or two percentage points between two
systems is usually noise. Paired significance testing over the same case set is
the honest way to compare, and reporting the per-case table alongside aggregates
lets a reader see whether a change helped broadly or moved a single outlier.
