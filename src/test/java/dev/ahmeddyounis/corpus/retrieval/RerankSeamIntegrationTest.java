package dev.ahmeddyounis.corpus.retrieval;

import dev.ahmeddyounis.corpus.security.UserAccount;
import dev.ahmeddyounis.corpus.security.UserRepository;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seam itself, independent of whether reranking improves any given query:
 * the right reranker is selected, the window is still bounded, ranks are
 * renumbered, and the API says which head produced the ordering.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RerankSeamIntegrationTest extends AbstractIntegrationTest {

    /**
     * Three documents, not one: the seam only does anything with a candidate set
     * to order, and the demo user starts empty under the test profile.
     */
    private static final Map<String, String> FIXTURES = Map.of(
            "rerank-seam-fusion.md", """
                    # Rerank seam fixture: fusion
                    Reciprocal Rank Fusion merges two rankings by position agreement,
                    summing one over k plus rank across the legs that found a chunk.
                    """,
            "rerank-seam-cross-encoder.md", """
                    # Rerank seam fixture: cross-encoder
                    A cross-encoder scores the query and the passage together in a
                    single forward pass, which is the signal fusion cannot see.
                    """,
            "rerank-seam-vectors.md", """
                    # Rerank seam fixture: vectors
                    A bi-encoder embeds query and passage separately, so their vectors
                    are compared without either having seen the other.
                    """);

    @Autowired
    private RetrievalService retrievalService;
    @Autowired
    private Reranker reranker;
    @Autowired
    private NoOpReranker noOpReranker;
    @Autowired
    private UserRepository users;

    private String token;
    private UUID userId;

    @BeforeAll
    void seed() {
        token = demoToken();
        List<Map<String, Object>> existing = listDocuments(token);
        FIXTURES.forEach((filename, body) -> {
            if (existing.stream().noneMatch(d -> filename.equals(d.get("filename"))
                    && "READY".equals(d.get("status")))) {
                uploadAndAwaitReady(token, filename, body.getBytes(StandardCharsets.UTF_8));
            }
        });
        userId = users.findByUsername("demo").map(UserAccount::id).orElseThrow();
    }

    /**
     * The model-backed reranker overrides the no-op through {@code @Primary}, not
     * {@code @ConditionalOnMissingBean}: both are scanned components, and the
     * ordering between scanned beans is undefined.
     */
    @Test
    void modelBackedRerankerWinsInjection() {
        assertThat(reranker).isInstanceOf(CrossEncoderReranker.class);
        assertThat(reranker.isReady()).isTrue();
        assertThat(noOpReranker.isReady()).isFalse();
    }

    /**
     * Fusion truncation moved out of {@link RetrievalService} and into the reranker.
     * If the service still sliced before the seam, the reranker could only reorder
     * what fusion already accepted — the exact mistake the seam prevents.
     */
    @Test
    void topKIsStillHonouredWithRerankingOn() {
        List<ScoredChunk> results = retrievalService.search(userId, "cross-encoder scores query and passage",
                2, null, true);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(ScoredChunk::rank).containsExactly(1, 2);
    }

    @Test
    void rerankedResultsCarryScoresAndAreOrderedByThem() {
        List<ScoredChunk> results = retrievalService.search(userId, "how does fusion merge two rankings",
                5, null, true);

        assertThat(results).hasSizeGreaterThan(1)
                .allSatisfy(chunk -> assertThat(chunk.rerankScore()).isNotNull());
        assertThat(results).extracting(ScoredChunk::rerankScore)
                .isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(results).extracting(ScoredChunk::rank).isSorted();
        // The fused score survives reranking; being able to see a chunk the
        // cross-encoder promoted from a low RRF rank is the point of keeping both.
        assertThat(results).allSatisfy(chunk -> assertThat(chunk.rrfScore()).isGreaterThan(0));
    }

    /**
     * The seam must produce a scored result even when there is nothing to reorder,
     * or the response shape would depend on how many candidates fusion happened to
     * return for a given query.
     */
    @Test
    void singleCandidateIsStillScored() {
        List<ScoredChunk> results = retrievalService.search(userId, "bi-encoder embeds separately", 1, null, true);

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().rerankScore()).isNotNull();
    }

    @Test
    void fusionOnlyPathLeavesRerankScoreNull() {
        assertThat(retrievalService.search(userId, "fusion", 5, null, false))
                .isNotEmpty()
                .allSatisfy(chunk -> assertThat(chunk.rerankScore()).isNull());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchApiReportsWhichRerankerRan() {
        Map<String, Object> reranked = search(Map.of("query", "fusion", "topK", 3, "rerank", true));
        Map<String, Object> fused = search(Map.of("query", "fusion", "topK", 3, "rerank", false));

        assertThat(reranked).containsEntry("reranker", "ms-marco-MiniLM-L-6-v2");
        assertThat(fused).containsEntry("reranker", "none");
        assertThat((List<Map<String, Object>>) reranked.get("results")).isNotEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> search(Map<String, Object> body) {
        return restClient().post().uri("/api/search")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
    }
}
