package dev.ahmeddyounis.corpus.retrieval;

import java.util.UUID;

/**
 * One retrieved chunk after fusion. {@code vectorScore} (cosine similarity) and
 * {@code ftsScore} ({@code ts_rank_cd}) are null when the chunk was found by only
 * the other leg; {@code rrfScore} is the fused ranking signal, and
 * {@code rerankScore} is the cross-encoder relevance logit, null when no
 * model-backed reranker ran.
 *
 * <p>All four scores are kept rather than collapsed into one: a reranked result
 * whose {@code rrfScore} was low is exactly the case a reranker exists to
 * produce, and being able to see that in the API response is what makes a
 * ranking change reviewable instead of a black box.
 */
public record ScoredChunk(
        UUID chunkId,
        UUID documentId,
        String filename,
        int chunkIndex,
        String content,
        int rank,
        double rrfScore,
        Double vectorScore,
        Double ftsScore,
        Double rerankScore) {

    public ScoredChunk withRank(int newRank) {
        return new ScoredChunk(chunkId, documentId, filename, chunkIndex, content,
                newRank, rrfScore, vectorScore, ftsScore, rerankScore);
    }

    public ScoredChunk withRerankScore(double score) {
        return new ScoredChunk(chunkId, documentId, filename, chunkIndex, content,
                rank, rrfScore, vectorScore, ftsScore, score);
    }
}
