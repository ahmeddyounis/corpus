# Token budgeting and context-window economics

Every prompt sent to a language model has a cost proportional to its length, and
in a retrieval-augmented system most of that length is retrieved context rather
than the user's question. This note covers budgeting in general terms.

## Where the tokens go

A typical RAG prompt is dominated by retrieved passages. A system prompt might
run 200 tokens, the user's question 20, and six retrieved chunks of 512 tokens
about 3,000 — so roughly nine tenths of input cost is context. The lever with
the most effect on spend is therefore how many chunks are injected and how large
each one is, not prompt wording.

## Input and output are not priced alike

Providers generally price output tokens several times higher than input. A
verbose answering style can cost more than the entire retrieved context, which
makes response-length instructions a genuine cost control and not merely a
stylistic preference.

## Diminishing returns from more context

Adding chunks raises the chance the answer is present, but each additional chunk
also dilutes attention and adds cost. Beyond a modest number the marginal
recall gain flattens while cost keeps rising linearly, and very long contexts
introduce position effects where material in the middle is attended to least.
The practical implication is that a smaller, better-ordered context frequently
outperforms a larger unordered one at lower cost.

## Estimating spend

Cost estimates multiply token counts by a per-million-token price table. Such
estimates are approximations: prices change, cached input may bill differently,
and locally hosted models cost nothing per token while consuming hardware. The
value of tracking estimated spend is trend detection and attribution by model,
not accounting precision.

## Caching as a cost lever

Two caches help for different reasons. Caching embeddings avoids recomputing
vectors for text that has not changed, which matters during re-ingestion and for
repeated queries. Caching answers avoids the model call entirely for repeated or
near-identical questions, which is the larger saving but demands care: an answer
is only reusable if the question is self-contained and the underlying corpus has
not changed since it was produced.

## Quotas

Measurement alone does not bound spend. Enforcing a per-user budget requires
durable accumulation of usage and a check before the model call, with a clear
error when the budget is exhausted. A fixed daily window is simple to reason
about and cheap to store; rolling windows are more precise and more expensive.
