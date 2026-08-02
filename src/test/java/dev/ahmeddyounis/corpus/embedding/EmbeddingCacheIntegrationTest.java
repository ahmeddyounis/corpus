package dev.ahmeddyounis.corpus.embedding;

import dev.ahmeddyounis.corpus.retrieval.RetrievalService;
import dev.ahmeddyounis.corpus.security.UserAccount;
import dev.ahmeddyounis.corpus.security.UserRepository;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the cache is actually in the path that matters. Nothing in the service
 * calls the embedding model directly — {@code PgVectorStore} embeds internally —
 * so a unit test of the wrapper says nothing about whether the wrapper is
 * reached. These assertions run against the real autoconfigured stack.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmbeddingCacheIntegrationTest extends AbstractIntegrationTest {

    private static final String FIXTURE = """
            # Embedding cache fixture
            Embeddings are a pure function of text, provider, model, and dimension,
            which is what makes them safely cacheable across users.
            """;

    @Autowired
    private EmbeddingModel embeddingModel;
    @Autowired
    private EmbeddingCacheDao dao;
    @Autowired
    private RetrievalService retrievalService;
    @Autowired
    private MeterRegistry registry;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcClient jdbc;

    private UUID userId;

    @BeforeAll
    void seed() {
        String token = demoToken();
        if (listDocuments(token).stream().noneMatch(d -> "embedding-cache.md".equals(d.get("filename"))
                && "READY".equals(d.get("status")))) {
            uploadAndAwaitReady(token, "embedding-cache.md", FIXTURE.getBytes(StandardCharsets.UTF_8));
        }
        userId = users.findByUsername("demo").map(UserAccount::id).orElseThrow();
    }

    @Test
    void theAutoconfiguredModelIsWrapped() {
        assertThat(embeddingModel).isInstanceOf(CachingEmbeddingModel.class);
        assertThat(((CachingEmbeddingModel) embeddingModel).namespace())
                .isEqualTo("transformers:all-MiniLM-L6-v2:384");
    }

    /**
     * Ingestion goes through {@code vectorStore.add}, which embeds internally.
     * Rows in the cache table after an upload prove the wrapper is on that path.
     */
    @Test
    void ingestionPopulatesTheSharedTier() {
        assertThat(dao.size("transformers:all-MiniLM-L6-v2:384")).isPositive();
    }

    @Test
    void repeatingAQueryServesTheEmbeddingFromCache() {
        String query = "what makes embeddings cacheable across users";
        retrievalService.search(userId, query, 3, null, false);
        double before = hits();

        retrievalService.search(userId, query, 3, null, false);

        assertThat(hits()).as("a repeated query must not re-embed").isGreaterThan(before);
    }

    /** The cache stores a digest and a vector — never the text it was derived from. */
    @Test
    void noSourceTextIsPersistedInTheCache() {
        var hashes = jdbc.sql("SELECT content_hash FROM embedding_cache").query(String.class).list();

        assertThat(hashes).isNotEmpty().allSatisfy(hash -> assertThat(hash).hasSize(64)
                .matches("[0-9a-f]{64}"));
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM information_schema.columns
                         WHERE table_name = 'embedding_cache' AND column_name IN ('content', 'text')
                        """).query(Long.class).single())
                .as("no plaintext column exists to leak")
                .isZero();
    }

    private double hits() {
        try {
            return registry.get("corpus.embedding.cache").tag("result", "hit").counters()
                    .stream().mapToDouble(c -> c.count()).sum();
        } catch (MeterNotFoundException e) {
            return 0;
        }
    }
}
