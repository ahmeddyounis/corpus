package dev.ahmeddyounis.corpus.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

    protected RestClient restClient() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }
}
