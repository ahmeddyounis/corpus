package dev.ahmeddyounis.corpus.support;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Base for all integration tests: one shared pgvector Postgres container for the
 * whole JVM (started eagerly, reused across test classes, reaped by Ryuk) and the
 * {@code test} profile (in-process ONNX embeddings, no chat model, 384-dim schema).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    @LocalServerPort
    protected int port;

    /** Client that never throws on 4xx/5xx so tests can assert status codes directly. */
    protected RestClient restClient() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    protected String demoToken() {
        Map<String, Object> body = restClient().post().uri("/api/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "demo", "password", "demo"))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return (String) body.get("token");
    }

    protected ResponseEntity<Map<String, Object>> uploadDocument(String token, String filename, byte[] bytes) {
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

    /** Unwraps the pagination envelope so callers keep working with a plain list. */
    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> listDocuments(String token) {
        Map<String, Object> page = restClient().get().uri("/api/documents?size=100")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return (List<Map<String, Object>>) page.get("items");
    }

    /** Uploads and blocks until ingestion reaches READY; returns the document id. */
    protected String uploadAndAwaitReady(String token, String filename, byte[] bytes) {
        ResponseEntity<Map<String, Object>> accepted = uploadDocument(token, filename, bytes);
        assertThat(accepted.getStatusCode().value()).isEqualTo(202);
        String documentId = (String) accepted.getBody().get("id");
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Map<String, Object> doc = listDocuments(token).stream()
                    .filter(d -> documentId.equals(d.get("id")))
                    .findFirst().orElseThrow();
            assertThat(doc.get("status")).as("error: %s", doc.get("error")).isEqualTo("READY");
        });
        return documentId;
    }
}
