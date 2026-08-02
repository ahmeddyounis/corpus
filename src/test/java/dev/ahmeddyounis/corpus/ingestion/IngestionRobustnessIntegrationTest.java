package dev.ahmeddyounis.corpus.ingestion;

import dev.ahmeddyounis.corpus.ops.InstanceIdentity;
import dev.ahmeddyounis.corpus.security.UserAccount;
import dev.ahmeddyounis.corpus.security.UserRepository;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private IngestionShutdownSweeper shutdownSweeper;
    @Autowired
    private DocumentLifecycleDao lifecycle;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private InstanceIdentity instance;
    @Autowired
    private JdbcClient jdbc;

    private UUID user(String name) {
        return users.findByUsername(name)
                .orElseGet(() -> users.save(UserAccount.create(name, passwordEncoder.encode("x"))))
                .id();
    }

    /** Inserts a row in an arbitrary in-flight state, bypassing the normal lifecycle. */
    private UUID inFlight(UUID userId, String filename, DocumentStatus status,
                          String ownerInstance, Instant claimedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO documents (id, user_id, filename, content_type, size_bytes,
                                               status, chunk_count, created_at, updated_at,
                                               owner_instance, claimed_at)
                        VALUES (:id, :userId, :filename, 'text/markdown', 10,
                                :status, 0, now(), now(), :owner, :claimedAt)
                        """)
                .param("id", id)
                .param("userId", userId)
                .param("filename", filename)
                .param("status", status.name())
                .param("owner", ownerInstance)
                .param("claimedAt", java.sql.Timestamp.from(claimedAt))
                .update();
        return id;
    }

    private int chunkCount(UUID documentId) {
        return jdbc.sql("SELECT count(*) FROM vector_store WHERE metadata->>'document_id' = :id")
                .param("id", documentId.toString())
                .query(Integer.class)
                .single();
    }

    /**
     * The regression test for rolling-deploy data corruption: a starting instance
     * must never fail work another live instance is doing.
     */
    @Test
    void sweepLeavesAnotherLiveInstancesRowsAlone() {
        UUID userId = user("sweep-live");
        UUID theirs = inFlight(userId, "their-live-doc.md", DocumentStatus.PROCESSING,
                "another-pod-still-running", Instant.now());

        sweeper.sweep();

        assertThat(documents.findById(theirs).orElseThrow().status())
                .as("another live instance's in-flight document must be untouched")
                .isEqualTo(DocumentStatus.PROCESSING);
    }

    @Test
    void sweepFailsThisInstancesOwnInterruptedRows() {
        UUID userId = user("sweep-own");
        UUID mine = inFlight(userId, "my-interrupted-doc.md", DocumentStatus.PROCESSING,
                instance.id(), Instant.now());

        assertThat(sweeper.sweep()).isPositive();

        DocumentEntity swept = documents.findById(mine).orElseThrow();
        assertThat(swept.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(swept.error()).contains("restart");
    }

    @Test
    void sweepFailsAnyInstancesAbandonedRows() {
        UUID userId = user("sweep-stale");
        UUID abandoned = inFlight(userId, "abandoned-doc.md", DocumentStatus.PENDING,
                "a-pod-that-never-came-back", Instant.now().minus(2, ChronoUnit.HOURS));

        sweeper.sweep();

        assertThat(documents.findById(abandoned).orElseThrow().status()).isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    void sweepRemovesOrphanedChunksOfSweptDocuments() {
        UUID userId = user("sweep-chunks");
        UUID id = inFlight(userId, "half-ingested.md", DocumentStatus.PROCESSING,
                instance.id(), Instant.now());
        String metadata = """
                {"user_id":"%s","document_id":"%s","filename":"half-ingested.md","chunk_index":0}
                """.formatted(userId, id);
        jdbc.sql("""
                        INSERT INTO vector_store (id, content, metadata, embedding)
                        VALUES (gen_random_uuid(), 'orphan chunk',
                                CAST(:metadata AS jsonb),
                                CAST(:embedding AS vector))
                        """)
                .param("metadata", metadata)
                .param("embedding", zeroVector())
                .update();
        assertThat(chunkCount(id)).isEqualTo(1);

        sweeper.sweep();

        assertThat(documents.findById(id).orElseThrow().status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(chunkCount(id))
                .as("a swept document must not leave searchable chunks behind")
                .isZero();
    }

    /** Once a document has been swept, a late-finishing worker must not revive it. */
    @Test
    void readyWriteCannotResurrectASweptDocument() {
        UUID userId = user("sweep-resurrect");
        UUID id = inFlight(userId, "late-finisher.md", DocumentStatus.PROCESSING,
                instance.id(), Instant.now());
        sweeper.sweep();
        assertThat(documents.findById(id).orElseThrow().status()).isEqualTo(DocumentStatus.FAILED);

        boolean applied = lifecycle.markReady(id, 7, instance.id());

        assertThat(applied).isFalse();
        DocumentEntity after = documents.findById(id).orElseThrow();
        assertThat(after.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(after.chunkCount()).isZero();
    }

    @Test
    void processingADeletedDocumentLeavesNoOrphanChunksAndDoesNotThrow() {
        UUID userId = user("race-user");
        DocumentEntity doc = documents.save(
                DocumentEntity.create(userId, "ghost.md", "text/markdown", 20, instance.id()));
        documents.deleteById(doc.id());

        ingestionService.process(doc, "# Ghost\nContent that would have been chunked.".getBytes());

        assertThat(chunkCount(doc.id())).isZero();
        assertThat(documents.findById(doc.id())).isEmpty();
    }

    @Test
    void shutdownSweepFailsOnlyThisInstancesLeftovers() {
        UUID userId = user("shutdown-sweep");
        UUID mine = inFlight(userId, "mine-at-shutdown.md", DocumentStatus.PROCESSING,
                instance.id(), Instant.now());
        UUID theirs = inFlight(userId, "theirs-at-shutdown.md", DocumentStatus.PROCESSING,
                "another-live-pod", Instant.now());

        shutdownSweeper.stop();

        assertThat(documents.findById(mine).orElseThrow().status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(documents.findById(theirs).orElseThrow().status()).isEqualTo(DocumentStatus.PROCESSING);
    }

    @Test
    void claimIsExclusiveAcrossInstances() {
        UUID userId = user("claim-race");
        UUID id = inFlight(userId, "contested.md", DocumentStatus.PENDING, instance.id(), Instant.now());

        assertThat(lifecycle.claim(id, "instance-a")).isTrue();
        assertThat(lifecycle.claim(id, "instance-b")).as("already PROCESSING").isFalse();
        assertThat(lifecycle.markReady(id, 3, "instance-b")).as("not the owner").isFalse();
        assertThat(lifecycle.markReady(id, 3, "instance-a")).isTrue();
    }

    private static String zeroVector() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 384; i++) {
            sb.append(i == 0 ? "0" : ",0");
        }
        return sb.append(']').toString();
    }
}
