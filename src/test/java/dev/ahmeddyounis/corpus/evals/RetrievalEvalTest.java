package dev.ahmeddyounis.corpus.evals;

import dev.ahmeddyounis.corpus.ingestion.DocumentStatus;
import dev.ahmeddyounis.corpus.ingestion.IngestionService;
import dev.ahmeddyounis.corpus.retrieval.RetrievalService;
import dev.ahmeddyounis.corpus.retrieval.ScoredChunk;
import dev.ahmeddyounis.corpus.security.UserAccount;
import dev.ahmeddyounis.corpus.security.UserRepository;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * build fails if quality regresses below the gates.
 *
 * <p>The corpus deliberately contains distractor documents that share vocabulary
 * with the real sources, so surface-level matching ranks the wrong document first.
 * Gates are set from measured runs minus a stated margin — never aspirationally.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetrievalEvalTest extends AbstractIntegrationTest {

    // Set from a measured run on the distractor-hardened corpus minus a 0.05
    // margin, never aspirationally: recall@3 0.875, recall@5 0.938, MRR 0.792,
    // nDCG@5 0.817. recall@3 is the primary gate — it has the most headroom for
    // a ranking improvement to show up in.
    private static final double RECALL_AT_3_GATE = 0.82;
    private static final double RECALL_AT_5_GATE = 0.88;
    private static final double MRR_GATE = 0.74;
    private static final double NDCG_AT_5_GATE = 0.76;

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
        await().atMost(Duration.ofMinutes(5)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            var docs = ingestionService.list(evalUserId);
            var failed = docs.stream().filter(d -> d.status() == DocumentStatus.FAILED).toList();
            assertThat(failed).as("failed ingestions: %s", failed).isEmpty();
            long ready = docs.stream().filter(d -> d.status() == DocumentStatus.READY).count();
            assertThat(ready).isEqualTo(expected);
        });
    }

    private List<EvalMetrics.CaseResult> run(List<GoldenSet.GoldenCase> cases) {
        List<EvalMetrics.CaseResult> results = new ArrayList<>();
        for (GoldenSet.GoldenCase goldenCase : cases) {
            List<ScoredChunk> chunks = retrievalService.search(evalUserId, goldenCase.question(), 5, null);
            results.add(EvalMetrics.CaseResult.of(goldenCase,
                    chunks.stream().map(ScoredChunk::filename).toList()));
        }
        return results;
    }

    @Test
    void retrievalQualityMeetsGates() throws Exception {
        List<GoldenSet.GoldenCase> cases = GoldenSet.load();
        assertThat(cases).hasSizeGreaterThanOrEqualTo(30);

        List<EvalMetrics.CaseResult> results = run(cases);
        EvalMetrics.Summary overall = EvalMetrics.Summary.of("all", results);

        // Reported separately because the distractor cases are the ones a ranking
        // improvement should move; the easy cases are already saturated.
        List<EvalMetrics.CaseResult> hard = results.stream()
                .filter(r -> r.tags().contains("hard"))
                .toList();
        EvalMetrics.Summary hardOnly = EvalMetrics.Summary.of("hard-only", hard);

        String report = """

                ===== Retrieval eval report =====
                %s
                %s
                %s
                gates: recall@3 >= %.2f, recall@5 >= %.2f, MRR >= %.2f, nDCG@5 >= %.2f
                =================================
                """.formatted(EvalMetrics.table(results), overall.describe(), hardOnly.describe(),
                RECALL_AT_3_GATE, RECALL_AT_5_GATE, MRR_GATE, NDCG_AT_5_GATE);
        System.out.println(report);

        Path out = Path.of("build/reports/evals");
        Files.createDirectories(out);
        Files.writeString(out.resolve("retrieval.json"), EvalMetrics.json(List.of(overall, hardOnly)));

        assertThat(overall.recallAt3()).as("recall@3 regression%n%s", report).isGreaterThanOrEqualTo(RECALL_AT_3_GATE);
        assertThat(overall.recallAt5()).as("recall@5 regression%n%s", report).isGreaterThanOrEqualTo(RECALL_AT_5_GATE);
        assertThat(overall.mrr()).as("MRR regression%n%s", report).isGreaterThanOrEqualTo(MRR_GATE);
        assertThat(overall.ndcgAt5()).as("nDCG@5 regression%n%s", report).isGreaterThanOrEqualTo(NDCG_AT_5_GATE);
    }
}
