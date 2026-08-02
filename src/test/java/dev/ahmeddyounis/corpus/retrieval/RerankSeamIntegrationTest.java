package dev.ahmeddyounis.corpus.retrieval;

import dev.ahmeddyounis.corpus.security.UserAccount;
import dev.ahmeddyounis.corpus.security.UserRepository;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
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
 * The seam is inert until a model-backed reranker is wired in. These assertions
 * are what let the eval numbers from the previous commit stand unchanged: with
 * the no-op reranker, {@code rerank=true} and {@code rerank=false} must return
 * byte-identical rankings.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RerankSeamIntegrationTest extends AbstractIntegrationTest {

    private static final String FIXTURE = """
            # Rerank seam fixture
            Reciprocal Rank Fusion merges two rankings by position agreement. A
            cross-encoder instead scores the query and the passage together, which
            is the signal fusion structurally cannot see.
            """;

    @Autowired
    private RetrievalService retrievalService;
    @Autowired
    private Reranker reranker;
    @Autowired
    private UserRepository users;

    private String token;
    private UUID userId;

    @BeforeAll
    void seed() {
        token = demoToken();
        if (listDocuments(token).stream().noneMatch(d -> "rerank-seam.md".equals(d.get("filename"))
                && "READY".equals(d.get("status")))) {
            uploadAndAwaitReady(token, "rerank-seam.md", FIXTURE.getBytes(StandardCharsets.UTF_8));
        }
        userId = users.findByUsername("demo").map(UserAccount::id).orElseThrow();
    }

    @Test
    void rerankingOnAndOffAreIdenticalUnderTheNoOpReranker() {
        String query = "how does fusion merge two rankings";

        List<ScoredChunk> off = retrievalService.search(userId, query, 5, null, false);
        List<ScoredChunk> on = retrievalService.search(userId, query, 5, null, true);

        assertThat(off).isNotEmpty();
        assertThat(on).containsExactlyElementsOf(off);
    }

    /**
     * Fusion truncation moved out of {@link RetrievalService} and into the reranker.
     * If the service still sliced before the seam, a real reranker could only
     * reorder what fusion already accepted — the exact mistake the seam prevents.
     */
    @Test
    void topKIsStillHonouredWithRerankingOn() {
        List<ScoredChunk> results = retrievalService.search(userId, "cross-encoder scores query and passage",
                3, null, true);

        assertThat(results).hasSizeLessThanOrEqualTo(3);
        assertThat(results).extracting(ScoredChunk::rank).isSorted();
        assertThat(results.getFirst().rank()).isEqualTo(1);
    }

    @Test
    void noOpRerankerLeavesRerankScoreNull() {
        assertThat(retrievalService.search(userId, "fusion", 5, null, true))
                .isNotEmpty()
                .allSatisfy(chunk -> assertThat(chunk.rerankScore()).isNull());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchApiReportsWhichRerankerRan() {
        Map<String, Object> body = restClient().post().uri("/api/search")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", "fusion", "topK", 3, "rerank", true))
                .retrieve()
                .body(Map.class);

        assertThat(body).containsEntry("reranker", reranker.name());
        assertThat((List<Map<String, Object>>) body.get("results")).isNotEmpty();
    }
}
