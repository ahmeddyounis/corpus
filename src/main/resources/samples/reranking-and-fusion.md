# Fusion and reranking: the two-stage retrieval pattern

Modern retrieval systems are usually staged: a cheap recall-oriented stage
gathers candidates, then an expensive precision-oriented stage reorders them.
This note describes the pattern generally.

## Why fuse at all

Different retrievers fail differently. Lexical search misses paraphrases because
it matches tokens; dense vector search misses rare identifiers, product codes,
and proper nouns because embeddings smooth them away. Running both and combining
the results recovers most of what either alone would lose.

## Rank fusion versus score fusion

Score fusion combines the retrievers' raw scores, which requires normalising
quantities that are not comparable — a cosine similarity in [0,1] and a BM25
score with no fixed upper bound. Normalisation schemes are corpus-dependent and
drift as data changes.

Rank fusion sidesteps this by discarding the scores and using only positions.
Reciprocal Rank Fusion assigns each document `1 / (k + rank)` from each list and
sums the contributions, where the constant `k` damps the influence of the very
top positions. Documents that appear in several lists accumulate several terms,
so cross-retriever agreement is what wins. The method has one parameter and no
training, which is why it is a common default.

## What reranking adds

Fusion reorders using position information only; it never reads the documents.
A cross-encoder does: it takes the query and a candidate together as one input
and scores their relevance jointly, so it can judge whether a passage actually
answers the question rather than merely resembling it.

The cost is that a cross-encoder cannot be precomputed. Bi-encoders embed
documents once at index time; a cross-encoder must run once per query-document
pair at query time. That is why reranking is applied to a shortlist — typically
the top 20 to 100 fused candidates — rather than the whole corpus.

## Sizing the stages

The first stage should over-retrieve relative to what the second stage returns.
Fusing to exactly the final *k* leaves the reranker nothing to do but permute;
fusing to several times *k* gives it the chance to promote something the first
stage ranked poorly, which is where most of the measured gain comes from.

Latency is the counterweight. Cross-encoder cost grows with candidate count and
with sequence length, and truncating passages more aggressively is usually the
cheapest lever when the budget is tight.

## Evaluating the stage

Reranking should be adopted on evidence, not faith. Run the evaluation set with
the stage disabled and enabled, and compare ordering-sensitive metrics — MRR and
nDCG move most, since the candidate set may be unchanged while its order
improves. If the evaluation set is small or easy enough that the first stage
already ranks everything correctly, the measurement will show nothing, and the
right response is to harden the evaluation set rather than to conclude that
reranking does not help.
