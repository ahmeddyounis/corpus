package dev.ahmeddyounis.corpus.retrieval;

import java.util.UUID;

/**
 * One retrieved chunk after fusion. {@code vectorScore} (cosine similarity) and
 * {@code ftsScore} ({@code ts_rank_cd}) are null when the chunk was found by only
 * the other leg; {@code rrfScore} is the fused ranking signal.
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
        Double ftsScore) {
}
