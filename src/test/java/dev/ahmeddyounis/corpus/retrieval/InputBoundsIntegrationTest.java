package dev.ahmeddyounis.corpus.retrieval;

import dev.ahmeddyounis.corpus.security.CurrentUser;
import dev.ahmeddyounis.corpus.security.UserAccount;
import dev.ahmeddyounis.corpus.security.UserRepository;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InputBoundsIntegrationTest extends AbstractIntegrationTest {

    private static final String FIXTURE = """
            # Bounds fixture
            Retrieval bounds are enforced in the service, not only at the API edge,
            because model-driven tool calls never pass through bean validation.
            """;

    @Autowired
    private RetrievalService retrievalService;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private String token;
    private UUID userId;

    @BeforeAll
    void seed() {
        token = demoToken();
        if (listDocuments(token).stream().noneMatch(d -> "bounds.md".equals(d.get("filename"))
                && "READY".equals(d.get("status")))) {
            uploadAndAwaitReady(token, "bounds.md", FIXTURE.getBytes(StandardCharsets.UTF_8));
        }
        userId = users.findByUsername("demo").map(UserAccount::id).orElseThrow();
    }

    private ResponseEntity<String> search(Map<String, Object> body) {
        return restClient().post().uri("/api/search")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }

    /**
     * The decisive assertion: MCP passes a model-supplied topK straight to the
     * service, so the clamp must live there. Bean validation alone would not help.
     */
    @Test
    void serviceLevelClampBoundsTheMcpPath() {
        List<ScoredChunk> results = retrievalService.search(userId, "retrieval bounds", 500, null);

        assertThat(results).hasSizeLessThanOrEqualTo(20);
    }

    @Test
    void nonPositiveTopKFallsBackToTheConfiguredDefault() {
        assertThat(retrievalService.search(userId, "retrieval bounds", 0, null)).isNotEmpty();
        assertThat(retrievalService.search(userId, "retrieval bounds", -5, null)).isNotEmpty();
    }

    @Test
    void overlongQueriesAreRejected() {
        Map<String, Object> body = new HashMap<>();
        body.put("query", "x".repeat(1001));

        assertThat(search(body).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void outOfRangeTopKIsRejectedAtTheApiEdge() {
        Map<String, Object> body = new HashMap<>();
        body.put("query", "retrieval bounds");
        body.put("topK", 500);

        assertThat(search(body).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void oversizedDocumentIdFiltersAreRejected() {
        Map<String, Object> body = new HashMap<>();
        body.put("query", "retrieval bounds");
        body.put("documentIds", java.util.stream.Stream.generate(UUID::randomUUID)
                .limit(51).map(UUID::toString).toList());

        assertThat(search(body).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void overlongChatMessagesAreRejected() {
        ResponseEntity<String> response = restClient().post().uri("/api/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", "y".repeat(4001)))
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void validRequestsStillSucceed() {
        Map<String, Object> body = new HashMap<>();
        body.put("query", "retrieval bounds");
        body.put("topK", 3);

        ResponseEntity<Map<String, Object>> ok = restClient().post().uri("/api/search")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {
                });
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat((int) ok.getBody().get("count")).isLessThanOrEqualTo(3);
    }
}
