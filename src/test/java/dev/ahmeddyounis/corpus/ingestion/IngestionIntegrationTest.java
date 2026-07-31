package dev.ahmeddyounis.corpus.ingestion;

import dev.ahmeddyounis.corpus.security.JwtService;
import dev.ahmeddyounis.corpus.security.UserAccount;
import dev.ahmeddyounis.corpus.security.UserRepository;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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

    private ResponseEntity<Map<String, Object>> upload(String token, String filename, byte[] bytes) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        return restClient().post().uri("/api/documents")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {
                });
    }

    private List<Map<String, Object>> listDocuments(String token) {
        return restClient().get().uri("/api/documents")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }

    @Test
    void uploadIngestsChunksAndDeleteCleansUp() throws Exception {
        String token = demoToken();

        ResponseEntity<Map<String, Object>> accepted = upload(token, "sla.md", fixture());
        assertThat(accepted.getStatusCode().value()).isEqualTo(202);
        String documentId = (String) accepted.getBody().get("id");
        assertThat(accepted.getBody().get("status")).isEqualTo("PENDING");

        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Map<String, Object> doc = listDocuments(token).stream()
                    .filter(d -> documentId.equals(d.get("id")))
                    .findFirst().orElseThrow();
            assertThat(doc.get("status")).as("error: %s", doc.get("error")).isEqualTo("READY");
        });

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
                upload(demoToken(), "malware.exe", "not really".getBytes());

        assertThat(response.getStatusCode().value()).isEqualTo(415);
    }

    @Test
    void documentsAreScopedPerUser() throws Exception {
        String demoToken = demoToken();
        ResponseEntity<Map<String, Object>> accepted = upload(demoToken, "scoped.md", fixture());
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
        ResponseEntity<Map<String, Object>> response = upload("not-a-token", "sla.md", "x".getBytes());

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}
