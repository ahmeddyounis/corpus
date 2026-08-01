package dev.ahmeddyounis.corpus.retrieval;

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
 * threads, then Reciprocal Rank Fusion merges both rankings on chunk id.
 * Every path is scoped to the calling user via chunk metadata.
 */
@Service
public class RetrievalService {

    private final VectorStore vectorStore;
    private final FullTextSearchDao fullTextSearch;
    private final RrfFuser fuser;
    private final CorpusRetrievalProperties properties;
    private final AsyncTaskExecutor retrievalExecutor;

    public RetrievalService(VectorStore vectorStore, FullTextSearchDao fullTextSearch, RrfFuser fuser,
                            CorpusRetrievalProperties properties,
                            @Qualifier("retrievalExecutor") AsyncTaskExecutor retrievalExecutor) {
        this.vectorStore = vectorStore;
        this.fullTextSearch = fullTextSearch;
        this.fuser = fuser;
        this.properties = properties;
        this.retrievalExecutor = retrievalExecutor;
    }

    public List<ScoredChunk> search(UUID userId, String query, Integer topKOverride, List<UUID> documentIds) {
        int candidateK = properties.candidateK();
        int topK = topKOverride != null && topKOverride > 0 ? topKOverride : properties.topK();

        CompletableFuture<List<Document>> vectorFuture =
                CompletableFuture.supplyAsync(() -> vectorSearch(userId, query, candidateK, documentIds),
                        retrievalExecutor);
        CompletableFuture<List<FtsHit>> ftsFuture =
                CompletableFuture.supplyAsync(() -> fullTextSearch.search(userId, query, candidateK, documentIds),
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

        List<ScoredChunk> results = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<UUID, Double> entry : fused.entrySet()) {
            if (rank > topK) {
                break;
            }
            UUID chunkId = entry.getKey();
            Document vector = vectorById.get(chunkId);
            FtsHit fts = ftsById.get(chunkId);
            results.add(toScoredChunk(chunkId, rank++, entry.getValue(), vector, fts));
        }
        return results;
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
                    fts != null ? fts.rank() : null);
        }
        return new ScoredChunk(chunkId, fts.documentId(), fts.filename(), fts.chunkIndex(), fts.content(),
                rank, rrfScore, null, fts.rank());
    }

    private List<Document> vectorSearch(UUID userId, String query, int candidateK, List<UUID> documentIds) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        var userScope = b.eq("user_id", userId.toString());
        Filter.Expression filter = (documentIds == null || documentIds.isEmpty())
                ? userScope.build()
                : b.and(userScope, b.in("document_id",
                        documentIds.stream().map(UUID::toString).map(Object.class::cast).toList())).build();

        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(candidateK)
                .filterExpression(filter)
                .build());
    }
}
