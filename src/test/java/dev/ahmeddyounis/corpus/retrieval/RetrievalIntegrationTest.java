package dev.ahmeddyounis.corpus.retrieval;

import dev.ahmeddyounis.corpus.security.JwtService;
import dev.ahmeddyounis.corpus.security.UserAccount;
import dev.ahmeddyounis.corpus.security.UserRepository;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetrievalIntegrationTest extends AbstractIntegrationTest {

    private static final String RUNBOOK = """
            # Payments gateway runbook
            The payments gateway signs every request with HMAC-SHA256 using key identifier ACM-9931.
            Signature validation failures return HTTP 401 with the error code SIG_MISMATCH. Rotate
            the signing secret quarterly via the key management console. The gateway keeps an
            allowlist of merchant IP ranges and rejects unlisted origins with code ORIGIN_BLOCKED.
            The latency budget for signature checks is two milliseconds at the 99th percentile.
            """;

    private static final String COOKING = """
            # Weeknight cooking notes
            Bring a large pot of water to a rolling boil and salt it generously. Slide the
            spaghetti in and stir for the first minute so nothing sticks. Taste a strand two
            minutes before the package time says done; you want it firm at the center. Reserve a
            cup of the starchy water, drain the rest, then toss everything with olive oil, garlic,
            and chili flakes over low heat, loosening the sauce with splashes of reserved water.
            """;

    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtService jwtService;

    private String token;
    private String runbookId;
    private String cookingId;

    @BeforeAll
    void seedCorpus() {
        token = demoToken();
        runbookId = existingId("runbook.md");
        cookingId = existingId("cooking.md");
        if (runbookId == null) {
            runbookId = uploadAndAwaitReady(token, "runbook.md", RUNBOOK.getBytes(StandardCharsets.UTF_8));
        }
        if (cookingId == null) {
            cookingId = uploadAndAwaitReady(token, "cooking.md", COOKING.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String existingId(String filename) {
        return listDocuments(token).stream()
                .filter(d -> filename.equals(d.get("filename")) && "READY".equals(d.get("status")))
                .map(d -> (String) d.get("id"))
                .findFirst().orElse(null);
    }

    private List<Map<String, Object>> search(String authToken, String query, List<String> documentIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("query", query);
        if (documentIds != null) {
            body.put("documentIds", documentIds);
        }
        Map<String, Object> response = restClient().post().uri("/api/search")
                .header("Authorization", "Bearer " + authToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
        return results;
    }

    @Test
    void keywordLegSurfacesExactIdentifiers() {
        List<Map<String, Object>> results = search(token, "ACM-9931", null);

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().get("filename")).isEqualTo("runbook.md");
        assertThat((Double) results.getFirst().get("rrfScore")).isPositive();
    }

    @Test
    void vectorLegSurfacesParaphrases() {
        List<Map<String, Object>> results =
                search(token, "how should I prepare Italian noodles for dinner", null);

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().get("filename")).isEqualTo("cooking.md");
        assertThat(results.getFirst().get("vectorScore")).isNotNull();
    }

    @Test
    void documentIdFilterRestrictsScope() {
        List<Map<String, Object>> results =
                search(token, "how should I prepare Italian noodles for dinner", List.of(runbookId));

        assertThat(results).noneMatch(r -> "cooking.md".equals(r.get("filename")));
    }

    @Test
    void searchIsUserScoped() {
        UserAccount other = users.findByUsername("searcher")
                .orElseGet(() -> users.save(UserAccount.create("searcher", passwordEncoder.encode("x"))));
        String otherToken = jwtService.issue(other.id(), other.username());

        assertThat(search(otherToken, "payments gateway signature", null)).isEmpty();
    }

    @Test
    void chunkPayloadCarriesProvenanceAndScores() {
        List<Map<String, Object>> results = search(token, "signature validation failures", null);

        assertThat(results).isNotEmpty();
        Map<String, Object> first = results.getFirst();
        assertThat(first).containsKeys("chunkId", "documentId", "filename", "chunkIndex",
                "content", "rank", "rrfScore");
        assertThat(first.get("rank")).isEqualTo(1);
    }
}
