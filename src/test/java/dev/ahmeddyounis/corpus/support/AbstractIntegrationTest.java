package dev.ahmeddyounis.corpus.support;

import java.util.Map;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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
}
