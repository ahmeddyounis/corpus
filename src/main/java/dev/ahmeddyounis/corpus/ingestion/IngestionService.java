package dev.ahmeddyounis.corpus.ingestion;

import dev.ahmeddyounis.corpus.ops.RagMetrics;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

/**
 * Upload → (async, virtual thread) parse → chunk → embed+store → status update.
 * Chunks land in the Spring AI vector store with user/document metadata for scoping.
 */
@Service
public class IngestionService {

    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "md", "markdown", "txt", "docx");

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final DocumentRepository documents;
    private final TikaTextExtractor extractor;
    private final TokenChunker chunker;
    private final VectorStore vectorStore;
    private final ExecutorService ingestionExecutor;
    private final RagMetrics metrics;

    public IngestionService(DocumentRepository documents, TikaTextExtractor extractor, TokenChunker chunker,
                            VectorStore vectorStore, ExecutorService ingestionExecutor, RagMetrics metrics) {
        this.documents = documents;
        this.extractor = extractor;
        this.chunker = chunker;
        this.vectorStore = vectorStore;
        this.ingestionExecutor = ingestionExecutor;
        this.metrics = metrics;
    }

    public static boolean supported(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SUPPORTED_EXTENSIONS.contains(name.substring(dot + 1));
    }

    public DocumentEntity upload(UUID userId, String filename, String contentType, byte[] bytes) {
        DocumentEntity saved = documents.save(DocumentEntity.create(userId, filename, contentType, bytes.length));
        ingestionExecutor.submit(() -> process(saved, bytes));
        return saved;
    }

    public List<DocumentEntity> list(UUID userId) {
        return documents.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public boolean delete(UUID userId, UUID documentId) {
        return documents.findByIdAndUserId(documentId, userId)
                .map(doc -> {
                    deleteChunks(userId, documentId);
                    documents.delete(doc);
                    return true;
                })
                .orElse(false);
    }

    void process(DocumentEntity doc, byte[] bytes) {
        if (!documents.existsById(doc.id())) {
            return;
        }
        DocumentEntity current = documents.save(doc.processing());
        try {
            String text = extractor.extract(new ByteArrayInputStream(bytes), doc.filename());
            List<TokenChunker.Chunk> chunks = chunker.chunk(text);
            if (chunks.isEmpty()) {
                throw new IngestionException("Document produced no chunks: " + doc.filename());
            }
            List<Document> aiDocuments = chunks.stream()
                    .map(chunk -> Document.builder()
                            .text(chunk.text())
                            .metadata(Map.of(
                                    "user_id", doc.userId().toString(),
                                    "document_id", doc.id().toString(),
                                    "filename", doc.filename(),
                                    "chunk_index", chunk.index()))
                            .build())
                    .toList();
            long embedStart = System.nanoTime();
            vectorStore.add(aiDocuments);
            metrics.recordPhase("embedding", (System.nanoTime() - embedStart) / 1_000_000);
            if (!documents.existsById(doc.id())) {
                // Deleted while we were embedding: discard the chunks just written.
                deleteChunks(doc.userId(), doc.id());
                log.info("Document {} was deleted during ingestion; discarded {} chunks",
                        doc.id(), chunks.size());
                return;
            }
            documents.save(current.ready(chunks.size()));
            log.info("Ingested document {} ({} chunks)", doc.filename(), chunks.size());
        } catch (Exception e) {
            log.error("Ingestion failed for document {}: {}", doc.id(), e.getMessage());
            try {
                deleteChunks(doc.userId(), doc.id());
            } catch (Exception cleanup) {
                log.warn("Chunk cleanup after failure also failed for {}: {}", doc.id(), cleanup.getMessage());
            }
            if (documents.existsById(doc.id())) {
                documents.save(current.failed(truncate(e.getMessage())));
            }
        }
    }

    private void deleteChunks(UUID userId, UUID documentId) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression expression = b.and(
                b.eq("user_id", userId.toString()),
                b.eq("document_id", documentId.toString())).build();
        vectorStore.delete(expression);
    }

    private static String truncate(String message) {
        if (message == null) {
            return "Unknown error";
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
