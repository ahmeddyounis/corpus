# Evaluating RAG systems: retrieval metrics and answer quality

A retrieval-augmented generation system fails in two distinct places: the
retriever misses the passage that contains the answer, or the generator
ignores or distorts the passage it was given. Corpus measures both, with
different tools, on different schedules.

## The golden set

Evaluation starts from a curated golden set: question, expected source
document, and reference answer triples over the seeded sample corpus. Each
sample document contributes at least two questions — one phrased with exact
keywords from the text, one paraphrased so that only semantic similarity can
find it. The set lives in version control (`evals/golden-set.yaml`) and
changes to it are reviewed like code, because the numbers below are only as
meaningful as the set is honest.

## Retrieval metrics: recall@5 and MRR

Recall@k asks the binary question per case: did any chunk from the expected
source document appear in the top k results? Mean Reciprocal Rank (MRR) is
stricter: it averages `1 / rank` of the first relevant result, so a system that
always finds the right document at position one scores 1.0, and one that finds
it at position three scores 0.33. nDCG@5 adds sensitivity to ordering within
the window, which recall is blind to, and precision@5 reports how much of the
returned window was actually relevant.

Corpus gates four of them: recall@3 ≥ 0.82, recall@5 ≥ 0.88, MRR ≥ 0.74, and
nDCG@5 ≥ 0.76. Together they catch different regressions — recall detects "we
lost it entirely," while MRR and nDCG detect "we still find it, but it slipped
down the list." recall@3 is the primary gate because it has the most headroom.

The corpus deliberately includes distractor documents that share vocabulary
with the real sources but answer a different question, so a system matching on
surface terms alone ranks the wrong document first. Without them the metrics
saturate at 1.000 and no ranking improvement can be demonstrated.

These retrieval metrics run on every pull request. They are fully
deterministic: the in-process ONNX embedding model produces identical vectors
on every run, Postgres full-text ranking is stable, and no API key or network
call is involved. A chunking tweak, a fusion-constant change, or a schema
migration that damages retrieval fails the build with a per-case table
showing exactly which questions regressed.

## Answer quality: LLM-as-judge

Whether a generated answer is faithful to its context cannot be asserted with
string equality. Corpus uses an LLM-as-judge approach for two properties.
Faithfulness: is every claim in the answer supported by the retrieved
context, with no hallucinated additions? Answer relevance: does the answer
actually address the question asked, rather than summarizing nearby content?
Both are scored 0.0–1.0 by a judge model given the question, the retrieved
context, and the answer; the build gates both at a mean of ≥ 0.80.

Judge-based metrics run nightly rather than per-PR: they need a real model,
cost real tokens, and carry inherent variance. Nightly cadence catches
regressions from prompt edits and model-version bumps within a day without
making every pull request slow, flaky, or dependent on a secret.

## Reading the numbers

A retrieval score drop with stable judge scores means the retriever changed —
look at chunking, fusion, or filters. Stable retrieval with falling
faithfulness means the generator is drifting — look at prompt changes or a
model swap. Both falling usually means the context window is being flooded
with irrelevant chunks: check top-k and the score spread gauge. The
before/after discipline matters most: every tuning change should ship with
eval numbers from both sides of the change, which is the difference between
engineering and folklore.
