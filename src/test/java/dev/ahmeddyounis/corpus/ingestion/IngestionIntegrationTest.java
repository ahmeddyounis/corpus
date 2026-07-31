package dev.ahmeddyounis.corpus.ingestion;

import dev.ahmeddyounis.corpus.security.JwtService;
import dev.ahmeddyounis.corpus.security.UserAccount;
import dev.ahmeddyounis.corpus.security.UserRepository;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtService jwtService;

    private byte[] fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/fixtures/sample.md")) {
            return in.readAllBytes();
        }
    }

    @Test
    void uploadIngestsChunksAndDeleteCleansUp() throws Exception {
        String token = demoToken();

        String documentId = uploadAndAwaitReady(token, "sla.md", fixture());

        Map<String, Object> doc = listDocuments(token).stream()
                .filter(d -> documentId.equals(d.get("id")))
                .findFirst().orElseThrow();
        int chunkCount = (int) doc.get("chunkCount");
        assertThat(chunkCount).isGreaterThanOrEqualTo(1);

        Integer storedChunks = jdbc.sql(
                        "SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = :id")
                .param("id", documentId)
                .query(Integer.class)
                .single();
        assertThat(storedChunks).isEqualTo(chunkCount);

        ResponseEntity<Void> deleted = restClient().delete().uri("/api/documents/" + documentId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .toBodilessEntity();
        assertThat(deleted.getStatusCode().value()).isEqualTo(204);

        Integer remaining = jdbc.sql(
                        "SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = :id")
                .param("id", documentId)
                .query(Integer.class)
                .single();
        assertThat(remaining).isZero();
    }

    @Test
    void rejectsUnsupportedExtensions() {
        ResponseEntity<Map<String, Object>> response =
                uploadDocument(demoToken(), "malware.exe", "not really".getBytes());

        assertThat(response.getStatusCode().value()).isEqualTo(415);
    }

    @Test
    void documentsAreScopedPerUser() throws Exception {
        String demoToken = demoToken();
        ResponseEntity<Map<String, Object>> accepted = uploadDocument(demoToken, "scoped.md", fixture());
        String documentId = (String) accepted.getBody().get("id");

        UserAccount other = users.findByUsername("intruder")
                .orElseGet(() -> users.save(UserAccount.create("intruder", passwordEncoder.encode("x"))));
        String otherToken = jwtService.issue(other.id(), other.username());

        assertThat(listDocuments(otherToken))
                .noneMatch(d -> documentId.equals(d.get("id")));

        ResponseEntity<Void> denied = restClient().delete().uri("/api/documents/" + documentId)
                .header("Authorization", "Bearer " + otherToken)
                .retrieve()
                .toBodilessEntity();
        assertThat(denied.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void openApiDocsAreServed() {
        ResponseEntity<String> response = restClient().get().uri("/v3/api-docs")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("/api/documents");
    }

    @Test
    void uploadRequiresAuthentication() {
        ResponseEntity<Map<String, Object>> response = uploadDocument("not-a-token", "sla.md", "x".getBytes());

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}
