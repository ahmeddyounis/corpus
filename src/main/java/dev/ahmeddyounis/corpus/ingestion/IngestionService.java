package dev.ahmeddyounis.corpus.ingestion;

import dev.ahmeddyounis.corpus.ops.InstanceIdentity;
import dev.ahmeddyounis.corpus.ops.RagMetrics;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * Upload → (async, virtual thread) parse → chunk → embed+store → status update.
 * Chunks land in the Spring AI vector store with user/document metadata for scoping.
 *
 * <p>Every status transition is compare-and-set guarded on this instance's ownership,
 * so a document reclaimed or deleted mid-ingestion is never silently resurrected —
 * the chunks written for it are discarded instead.
 */
@Service
public class IngestionService {

    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "md", "markdown", "txt", "docx");

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final DocumentRepository documents;
    private final DocumentLifecycleDao lifecycle;
    private final TikaTextExtractor extractor;
    private final TokenChunker chunker;
    private final VectorStore vectorStore;
    private final ChunkStore chunkStore;
    private final AsyncTaskExecutor ingestionExecutor;
    private final RagMetrics metrics;
    private final InstanceIdentity instance;

    public IngestionService(DocumentRepository documents, DocumentLifecycleDao lifecycle,
                            TikaTextExtractor extractor, TokenChunker chunker, VectorStore vectorStore,
                            ChunkStore chunkStore, AsyncTaskExecutor ingestionExecutor, RagMetrics metrics,
                            InstanceIdentity instance) {
        this.documents = documents;
        this.lifecycle = lifecycle;
        this.extractor = extractor;
        this.chunker = chunker;
        this.vectorStore = vectorStore;
        this.chunkStore = chunkStore;
        this.ingestionExecutor = ingestionExecutor;
        this.metrics = metrics;
        this.instance = instance;
    }

    public static boolean supported(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SUPPORTED_EXTENSIONS.contains(name.substring(dot + 1));
    }

    public DocumentEntity upload(UUID userId, String filename, String contentType, byte[] bytes) {
        DocumentEntity saved = documents.save(
                DocumentEntity.create(userId, filename, contentType, bytes.length, instance.id()));
        ingestionExecutor.submit(() -> {
            try {
                process(saved, bytes);
            } catch (Throwable t) {
                // The Future is discarded; without this, task failures would vanish
                // without a log line.
                log.error("Ingestion task for document {} failed unexpectedly", saved.id(), t);
            }
        });
        return saved;
    }

    public List<DocumentEntity> list(UUID userId) {
        return documents.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public boolean delete(UUID userId, UUID documentId) {
        return documents.findByIdAndUserId(documentId, userId)
                .map(doc -> {
                    chunkStore.deleteFor(userId, documentId);
                    documents.delete(doc);
                    return true;
                })
                .orElse(false);
    }

    void process(DocumentEntity doc, byte[] bytes) {
        String instanceId = instance.id();
        if (!lifecycle.claim(doc.id(), instanceId)) {
            log.info("Document {} is no longer claimable (deleted, or taken by another instance); skipping",
                    doc.id());
            return;
        }
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

            if (!lifecycle.markReady(doc.id(), chunks.size(), instanceId)) {
                // Deleted, swept, or reclaimed while we were embedding: the chunks we
                // just wrote belong to a document that no longer exists in this state.
                chunkStore.deleteFor(doc.userId(), doc.id());
                log.warn("Document {} was reclaimed during ingestion; discarded {} chunks",
                        doc.id(), chunks.size());
                return;
            }
            log.info("Ingested document {} ({} chunks)", doc.filename(), chunks.size());
        } catch (Exception e) {
            log.error("Ingestion failed for document {}: {}", doc.id(), e.getMessage());
            try {
                chunkStore.deleteFor(doc.userId(), doc.id());
            } catch (Exception cleanup) {
                log.warn("Chunk cleanup after failure also failed for {}: {}", doc.id(), cleanup.getMessage());
            }
            lifecycle.markFailed(doc.id(), truncate(e.getMessage()), instanceId);
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return "Unknown error";
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
