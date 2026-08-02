package dev.ahmeddyounis.corpus.retrieval;

import java.util.List;

/**
 * Second-stage ranking over the fused candidate set.
 *
 * <p>Reciprocal Rank Fusion ranks by <em>position agreement</em> between the two
 * legs — it never reads the query and a chunk together, so it cannot tell a
 * passage that mentions the query terms from one that answers the question. A
 * reranker scores each (query, passage) pair directly and reorders on that.
 *
 * <p>The seam takes the whole fused candidate list and returns the final window,
 * so truncation is the reranker's decision rather than something that already
 * happened upstream: a reranker handed a pre-truncated list can only reorder
 * results the first stage already accepted, which is precisely the mistake it
 * exists to correct.
 *
 * <p>Implementations must be fail-open. Retrieval degrading to fusion order is a
 * quality regression; retrieval throwing is an outage.
 */
public interface Reranker {

    /**
     * Reorders {@code candidates} (in fused order, ranks already assigned) and
     * returns at most {@code topK}, renumbered from 1.
     */
    List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK);

    /**
     * Whether real reranking is available. False for the no-op, and false for a
     * model-backed reranker whose model failed to load — evals assert on this so
     * a silently fail-open reranker cannot produce a green build.
     */
    default boolean isReady() {
        return false;
    }

    /** Identifier for logs, metrics tags, and the search response. */
    String name();

    /** Renumbers ranks 1..n over an already-ordered list, preserving all other fields. */
    static List<ScoredChunk> renumber(List<ScoredChunk> ordered) {
        List<ScoredChunk> out = new java.util.ArrayList<>(ordered.size());
        int rank = 1;
        for (ScoredChunk chunk : ordered) {
            out.add(chunk.withRank(rank++));
        }
        return out;
    }
}
