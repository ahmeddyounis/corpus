package dev.ahmeddyounis.corpus.ingestion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Token-based chunking knobs; bind from {@code CORPUS_CHUNK_SIZE} / {@code CORPUS_CHUNK_OVERLAP}. */
@ConfigurationProperties(prefix = "corpus.chunk")
public record CorpusChunkProperties(int size, int overlap) {

    public CorpusChunkProperties {
        if (size <= 0) {
            throw new IllegalArgumentException("corpus.chunk.size must be positive");
        }
        if (overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException("corpus.chunk.overlap must be in [0, size)");
        }
    }
}
