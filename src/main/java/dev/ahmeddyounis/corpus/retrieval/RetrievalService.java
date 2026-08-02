package dev.ahmeddyounis.corpus.retrieval;

import dev.ahmeddyounis.corpus.ops.ModelResilience;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import dev.ahmeddyounis.corpus.retrieval.FullTextSearchDao.FtsHit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Hybrid retrieval: the vector and full-text legs run in parallel on virtual
 * threads, then Reciprocal Rank Fusion merges both rankings on chunk id and a
 * {@link Reranker} picks the final window. Every path is scoped to the calling
 * user via chunk metadata.
 */
@Service
public class RetrievalService {

    private final VectorStore vectorStore;
    private final FullTextSearchDao fullTextSearch;
    private final RrfFuser fuser;
    private final CorpusRetrievalProperties properties;
    private final CorpusRerankProperties rerankProperties;
    private final Reranker reranker;
    private final AsyncTaskExecutor retrievalExecutor;
    private final ModelResilience resilience;
    private final ObservationRegistry observations;

    public RetrievalService(VectorStore vectorStore, FullTextSearchDao fullTextSearch, RrfFuser fuser,
                            CorpusRetrievalProperties properties, CorpusRerankProperties rerankProperties,
                            Reranker reranker,
                            @Qualifier("retrievalExecutor") AsyncTaskExecutor retrievalExecutor,
                            ModelResilience resilience, ObservationRegistry observations) {
        this.vectorStore = vectorStore;
        this.fullTextSearch = fullTextSearch;
        this.fuser = fuser;
        this.properties = properties;
        this.rerankProperties = rerankProperties;
        this.reranker = reranker;
        this.retrievalExecutor = retrievalExecutor;
        this.resilience = resilience;
        this.observations = observations;
    }

    public List<ScoredChunk> search(UUID userId, String query, Integer topKOverride, List<UUID> documentIds) {
        return search(userId, query, topKOverride, documentIds, null);
    }

    /**
     * @param rerank null to use the configured default; an explicit value overrides
     *               it for this call, which is what makes an A/B against a live
     *               index possible without a redeploy.
     */
    public List<ScoredChunk> search(UUID userId, String query, Integer topKOverride, List<UUID> documentIds,
                                    Boolean rerank) {
        int candidateK = properties.candidateK();
        int requested = topKOverride != null && topKOverride > 0 ? topKOverride : properties.topK();
        // Clamped here rather than only at the controller: MCP tool arguments come
        // from a model and never pass through bean validation. The previous ceiling
        // was an accident of candidateK, not a control.
        int topK = Math.clamp(requested, 1, properties.maxTopK());

        // Named observations so the parallel fan-out renders as two sibling spans
        // under the request, which is what makes a trace of this pipeline readable.
        CompletableFuture<List<Document>> vectorFuture =
                CompletableFuture.supplyAsync(() -> Observation
                                .createNotStarted("corpus.retrieval.vector", observations)
                                .lowCardinalityKeyValue("leg", "vector")
                                .observe(() -> vectorSearch(userId, query, candidateK, documentIds)),
                        retrievalExecutor);
        CompletableFuture<List<FtsHit>> ftsFuture =
                CompletableFuture.supplyAsync(() -> Observation
                                .createNotStarted("corpus.retrieval.fts", observations)
                                .lowCardinalityKeyValue("leg", "fulltext")
                                .observe(() -> fullTextSearch.search(userId, query, candidateK, documentIds)),
                        retrievalExecutor);

        List<Document> vectorHits = vectorFuture.join();
        List<FtsHit> ftsHits = ftsFuture.join();

        Map<UUID, Document> vectorById = new HashMap<>();
        List<UUID> vectorRanking = new ArrayList<>();
        for (Document doc : vectorHits) {
            UUID id = UUID.fromString(doc.getId());
            vectorById.put(id, doc);
            vectorRanking.add(id);
        }
        Map<UUID, FtsHit> ftsById = new HashMap<>();
        List<UUID> ftsRanking = new ArrayList<>();
        for (FtsHit hit : ftsHits) {
            ftsById.put(hit.chunkId(), hit);
            ftsRanking.add(hit.chunkId());
        }

        Map<UUID, Double> fused = fuser.fuse(properties.rrfK(), vectorRanking, ftsRanking);

        // The whole fused set is materialised, not just the top-k slice: the reranker
        // is what decides the final window, and one handed a pre-truncated list could
        // only reorder what fusion already accepted.
        List<ScoredChunk> candidates = new ArrayList<>(fused.size());
        int rank = 1;
        for (Map.Entry<UUID, Double> entry : fused.entrySet()) {
            UUID chunkId = entry.getKey();
            candidates.add(toScoredChunk(chunkId, rank++, entry.getValue(),
                    vectorById.get(chunkId), ftsById.get(chunkId)));
        }

        boolean applyRerank = rerank != null ? rerank : rerankProperties.enabled();
        if (!applyRerank) {
            return candidates.size() <= topK ? candidates : new ArrayList<>(candidates.subList(0, topK));
        }
        int window = topK;
        return Observation.createNotStarted("corpus.retrieval.rerank", observations)
                .lowCardinalityKeyValue("reranker", reranker.name())
                .observe(() -> reranker.rerank(query, candidates, window));
    }

    private static ScoredChunk toScoredChunk(UUID chunkId, int rank, double rrfScore, Document vector, FtsHit fts) {
        if (vector != null) {
            Map<String, Object> metadata = vector.getMetadata();
            return new ScoredChunk(chunkId,
                    UUID.fromString(String.valueOf(metadata.get("document_id"))),
                    String.valueOf(metadata.get("filename")),
                    metadata.get("chunk_index") instanceof Number n ? n.intValue() : 0,
                    vector.getText(),
                    rank, rrfScore,
                    vector.getScore(),
                    fts != null ? fts.rank() : null,
                    null);
        }
        return new ScoredChunk(chunkId, fts.documentId(), fts.filename(), fts.chunkIndex(), fts.content(),
                rank, rrfScore, null, fts.rank(), null);
    }

    private List<Document> vectorSearch(UUID userId, String query, int candidateK, List<UUID> documentIds) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        var userScope = b.eq("user_id", userId.toString());
        Filter.Expression filter = (documentIds == null || documentIds.isEmpty())
                ? userScope.build()
                : b.and(userScope, b.in("document_id",
                        documentIds.stream().map(UUID::toString).map(Object.class::cast).toList())).build();

        return resilience.callEmbedding(() -> vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(candidateK)
                .filterExpression(filter)
                .build()));
    }
}
