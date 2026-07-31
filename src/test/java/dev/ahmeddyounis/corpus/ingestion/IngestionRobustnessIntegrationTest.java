package dev.ahmeddyounis.corpus.ingestion;

import dev.ahmeddyounis.corpus.security.UserAccount;
import dev.ahmeddyounis.corpus.security.UserRepository;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionRobustnessIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private IngestionService ingestionService;
    @Autowired
    private StaleIngestionSweeper sweeper;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcClient jdbc;

    private UUID user(String name) {
        return users.findByUsername(name)
                .orElseGet(() -> users.save(UserAccount.create(name, passwordEncoder.encode("x"))))
                .id();
    }

    @Test
    void sweeperMarksInterruptedIngestionsFailed() {
        UUID userId = user("sweep-user");
        DocumentEntity pending = documents.save(
                DocumentEntity.create(userId, "stuck-pending.md", "text/markdown", 10));
        DocumentEntity processing = documents.save(
                DocumentEntity.create(userId, "stuck-processing.md", "text/markdown", 10).processing());

        int swept = sweeper.sweep();

        assertThat(swept).isGreaterThanOrEqualTo(2);
        assertThat(documents.findById(pending.id()).orElseThrow().status()).isEqualTo(DocumentStatus.FAILED);
        DocumentEntity afterProcessing = documents.findById(processing.id()).orElseThrow();
        assertThat(afterProcessing.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(afterProcessing.error()).contains("restart");
    }

    @Test
    void processingADeletedDocumentLeavesNoOrphanChunksAndDoesNotThrow() {
        UUID userId = user("race-user");
        DocumentEntity doc = documents.save(
                DocumentEntity.create(userId, "ghost.md", "text/markdown", 20));
        documents.deleteById(doc.id());

        ingestionService.process(doc, "# Ghost\nContent that would have been chunked.".getBytes());

        Integer chunks = jdbc.sql("SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = :id")
                .param("id", doc.id().toString())
                .query(Integer.class)
                .single();
        assertThat(chunks).isZero();
        assertThat(documents.findById(doc.id())).isEmpty();
    }
}
