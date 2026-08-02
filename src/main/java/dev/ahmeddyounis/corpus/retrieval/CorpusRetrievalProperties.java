package dev.ahmeddyounis.corpus.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retrieval knobs: {@code topK} chunks reach the prompt ({@code CORPUS_RETRIEVAL_TOP_K}),
 * {@code rrfK} is the Reciprocal Rank Fusion constant ({@code CORPUS_RRF_K}), and
 * {@code candidateK} is how many candidates each leg contributes before fusion.
 */
@ConfigurationProperties(prefix = "corpus.retrieval")
public record CorpusRetrievalProperties(int topK, int rrfK, int candidateK, int maxTopK) {

    public CorpusRetrievalProperties {
        maxTopK = maxTopK > 0 ? maxTopK : 20;
    }
}
