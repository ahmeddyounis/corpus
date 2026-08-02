package dev.ahmeddyounis.corpus.ingestion;

import java.util.UUID;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

/**
 * Deletion of a document's chunks, kept behind the Spring AI {@link VectorStore}
 * abstraction (see ADR 0002) and shared by the ingestion pipeline and both sweepers.
 */
@Component
public class ChunkStore {

    private final VectorStore vectorStore;

    public ChunkStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void deleteFor(UUID userId, UUID documentId) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression expression = b.and(
                b.eq("user_id", userId.toString()),
                b.eq("document_id", documentId.toString())).build();
        vectorStore.delete(expression);
    }
}
