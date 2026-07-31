package dev.ahmeddyounis.corpus.evals;

import dev.ahmeddyounis.corpus.ingestion.DocumentStatus;
import dev.ahmeddyounis.corpus.ingestion.IngestionService;
import dev.ahmeddyounis.corpus.retrieval.RetrievalService;
import dev.ahmeddyounis.corpus.retrieval.ScoredChunk;
import dev.ahmeddyounis.corpus.security.UserAccount;
import dev.ahmeddyounis.corpus.security.UserRepository;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * PR-tier retrieval evals: the golden set runs against a real ingestion of the
 * bundled sample corpus with deterministic in-process ONNX embeddings, and the
 * build fails if recall@5 or MRR regress below the gates.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetrievalEvalTest extends AbstractIntegrationTest {

    private static final double RECALL_AT_5_GATE = 0.85;
    private static final double MRR_GATE = 0.70;

    @Autowired
    private IngestionService ingestionService;
    @Autowired
    private RetrievalService retrievalService;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID evalUserId;

    @BeforeAll
    void ingestSampleCorpus() throws Exception {
        UserAccount evalUser = users.findByUsername("eval-user")
                .orElseGet(() -> users.save(UserAccount.create("eval-user", passwordEncoder.encode("x"))));
        evalUserId = evalUser.id();

        Set<String> present = ingestionService.list(evalUserId).stream()
                .filter(d -> d.status() == DocumentStatus.READY)
                .map(d -> d.filename())
                .collect(Collectors.toSet());

        Resource[] samples = new PathMatchingResourcePatternResolver().getResources("classpath:samples/*.md");
        assertThat(samples).as("bundled sample corpus").isNotEmpty();
        for (Resource sample : samples) {
            if (!present.contains(sample.getFilename())) {
                ingestionService.upload(evalUserId, sample.getFilename(), "text/markdown",
                        sample.getContentAsByteArray());
            }
        }

        int expected = samples.length;
        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            var docs = ingestionService.list(evalUserId);
            var failed = docs.stream().filter(d -> d.status() == DocumentStatus.FAILED).toList();
            assertThat(failed).as("failed ingestions: %s", failed).isEmpty();
            long ready = docs.stream().filter(d -> d.status() == DocumentStatus.READY).count();
            assertThat(ready).isEqualTo(expected);
        });
    }

    @Test
    void retrievalQualityMeetsGates() {
        List<GoldenSet.GoldenCase> cases = GoldenSet.load();
        assertThat(cases).hasSizeGreaterThanOrEqualTo(12);

        List<EvalMetrics.CaseResult> results = new ArrayList<>();
        for (GoldenSet.GoldenCase goldenCase : cases) {
            List<ScoredChunk> chunks = retrievalService.search(evalUserId, goldenCase.question(), 5, null);
            results.add(EvalMetrics.CaseResult.of(goldenCase,
                    chunks.stream().map(ScoredChunk::filename).toList()));
        }

        double recall = EvalMetrics.recallAtK(results, 5);
        double mrr = EvalMetrics.meanReciprocalRank(results);
        String report = """

                ===== Retrieval eval report =====
                %s
                recall@5 = %.3f (gate %.2f)   MRR = %.3f (gate %.2f)
                =================================
                """.formatted(EvalMetrics.table(results), recall, RECALL_AT_5_GATE, mrr, MRR_GATE);
        System.out.println(report);

        assertThat(recall).as("recall@5 regression%n%s", report).isGreaterThanOrEqualTo(RECALL_AT_5_GATE);
        assertThat(mrr).as("MRR regression%n%s", report).isGreaterThanOrEqualTo(MRR_GATE);
    }
}
