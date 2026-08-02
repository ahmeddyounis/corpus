package dev.ahmeddyounis.corpus.evals;

import dev.ahmeddyounis.corpus.ingestion.DocumentStatus;
import dev.ahmeddyounis.corpus.ingestion.IngestionService;
import dev.ahmeddyounis.corpus.retrieval.Reranker;
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
 * bundled sample corpus with deterministic in-process ONNX models, and the build
 * fails if quality regresses below the gates.
 *
 * <p>The corpus deliberately contains distractor documents that share vocabulary
 * with the real sources, so surface-level matching ranks the wrong document first.
 * Gates are set from measured runs minus a stated margin — never aspirationally.
 *
 * <p>Every case runs twice, with reranking off and on, so the effect of the
 * cross-encoder is a measured delta in the build output rather than a claim.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetrievalEvalTest extends AbstractIntegrationTest {

    // Set from a measured run on the distractor-hardened corpus minus a 0.05
    // margin, never aspirationally. Baseline (fusion only): recall@3 0.875,
    // recall@5 0.938, MRR 0.792, nDCG@5 0.814.
    private static final double RECALL_AT_3_GATE = 0.82;
    private static final double RECALL_AT_5_GATE = 0.88;
    private static final double MRR_GATE = 0.74;
    private static final double NDCG_AT_5_GATE = 0.76;

    // Reranked gates, same measured-minus-margin rule: measured MRR 0.883 and
    // nDCG@5 0.879 with the cross-encoder head.
    private static final double RERANKED_MRR_GATE = 0.83;
    private static final double RERANKED_NDCG_AT_5_GATE = 0.82;

    @Autowired
    private IngestionService ingestionService;
    @Autowired
    private RetrievalService retrievalService;
    @Autowired
    private Reranker reranker;
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

    /** Mean wall-clock per query, in milliseconds, for the most recent {@link #run}. */
    private double lastRunMillisPerQuery;

    private List<EvalMetrics.CaseResult> run(List<GoldenSet.GoldenCase> cases, boolean rerank, int topK) {
        List<EvalMetrics.CaseResult> results = new ArrayList<>();
        long start = System.nanoTime();
        for (GoldenSet.GoldenCase goldenCase : cases) {
            List<ScoredChunk> chunks = retrievalService.search(evalUserId, goldenCase.question(), topK, null, rerank);
            results.add(EvalMetrics.CaseResult.of(goldenCase,
                    chunks.stream().map(ScoredChunk::filename).toList()));
        }
        lastRunMillisPerQuery = (System.nanoTime() - start) / 1_000_000.0 / cases.size();
        return results;
    }

    /**
     * A fail-open reranker degrades to fusion order silently, so without this the
     * suite could report "reranking is fine" having never loaded the model.
     */
    @Test
    void crossEncoderIsActuallyLoaded() {
        assertThat(reranker.isReady())
                .as("cross-encoder must be loaded, otherwise the delta below measures nothing")
                .isTrue();
        assertThat(reranker.name()).isEqualTo("ms-marco-MiniLM-L-6-v2");
    }

    @Test
    void retrievalQualityMeetsGates() throws Exception {
        List<GoldenSet.GoldenCase> cases = GoldenSet.load();
        assertThat(cases).hasSizeGreaterThanOrEqualTo(30);

        // Warm both paths first: the first query pays ONNX session warmup and JIT,
        // which would otherwise be attributed to reranking as a latency cost.
        run(cases.subList(0, 3), false, 5);
        run(cases.subList(0, 3), true, 5);

        List<EvalMetrics.CaseResult> baseline = run(cases, false, 5);
        double baselineMillis = lastRunMillisPerQuery;
        List<EvalMetrics.CaseResult> reranked = run(cases, true, 5);
        double rerankedMillis = lastRunMillisPerQuery;
        EvalMetrics.Summary overall = EvalMetrics.Summary.of("fusion-only", baseline);
        EvalMetrics.Summary overallReranked = EvalMetrics.Summary.of("reranked", reranked);

        // Reported separately because the distractor cases are the ones a ranking
        // improvement should move; the easy cases are already saturated.
        EvalMetrics.Summary hardOnly = EvalMetrics.Summary.of("fusion-only-hard", hard(baseline));
        EvalMetrics.Summary hardReranked = EvalMetrics.Summary.of("reranked-hard", hard(reranked));

        // topK=3 is where a reranker pays off most: the window is too narrow to
        // absorb a distractor that fusion put first.
        EvalMetrics.Summary top3 = EvalMetrics.Summary.of("fusion-only-top3", run(cases, false, 3));
        EvalMetrics.Summary top3Reranked = EvalMetrics.Summary.of("reranked-top3", run(cases, true, 3));

        String report = """

                ===== Retrieval eval report =====
                %s
                %s
                %s
                %s
                %s
                %s
                %s

                --- rerank delta (topK=5) ---
                %s
                --- rerank delta (topK=3) ---
                %s
                --- per-case movement (topK=5) ---
                %s
                latency: fusion-only %.1f ms/query, reranked %.1f ms/query (+%.1f ms)
                gates: recall@3 >= %.2f, recall@5 >= %.2f, MRR >= %.2f, nDCG@5 >= %.2f
                       reranked MRR >= %.2f, reranked nDCG@5 >= %.2f
                =================================
                """.formatted(EvalMetrics.table(reranked),
                overall.describe(), overallReranked.describe(),
                hardOnly.describe(), hardReranked.describe(),
                top3.describe(), top3Reranked.describe(),
                EvalMetrics.delta(overall, overallReranked),
                EvalMetrics.delta(top3, top3Reranked),
                EvalMetrics.movement(baseline, reranked),
                baselineMillis, rerankedMillis, rerankedMillis - baselineMillis,
                RECALL_AT_3_GATE, RECALL_AT_5_GATE, MRR_GATE, NDCG_AT_5_GATE,
                RERANKED_MRR_GATE, RERANKED_NDCG_AT_5_GATE);
        System.out.println(report);

        Path out = Path.of("build/reports/evals");
        Files.createDirectories(out);
        Files.writeString(out.resolve("retrieval.json"), EvalMetrics.json(List.of(
                overall, overallReranked, hardOnly, hardReranked, top3, top3Reranked)));

        assertThat(overall.recallAt3()).as("recall@3 regression%n%s", report).isGreaterThanOrEqualTo(RECALL_AT_3_GATE);
        assertThat(overall.recallAt5()).as("recall@5 regression%n%s", report).isGreaterThanOrEqualTo(RECALL_AT_5_GATE);
        assertThat(overall.mrr()).as("MRR regression%n%s", report).isGreaterThanOrEqualTo(MRR_GATE);
        assertThat(overall.ndcgAt5()).as("nDCG@5 regression%n%s", report).isGreaterThanOrEqualTo(NDCG_AT_5_GATE);

        // Non-regression against the fusion-only head. Reranking that made ordering
        // worse would still pass the absolute gates above, since those were set
        // from the fusion-only baseline.
        assertThat(overallReranked.mrr()).as("reranking made MRR worse%n%s", report)
                .isGreaterThanOrEqualTo(overall.mrr());
        assertThat(overallReranked.ndcgAt5()).as("reranking made nDCG@5 worse%n%s", report)
                .isGreaterThanOrEqualTo(overall.ndcgAt5());
        assertThat(top3Reranked.recallAt3()).as("reranking made recall@3 worse at topK=3%n%s", report)
                .isGreaterThanOrEqualTo(top3.recallAt3());

        assertThat(overallReranked.mrr()).as("reranked MRR regression%n%s", report)
                .isGreaterThanOrEqualTo(RERANKED_MRR_GATE);
        assertThat(overallReranked.ndcgAt5()).as("reranked nDCG@5 regression%n%s", report)
                .isGreaterThanOrEqualTo(RERANKED_NDCG_AT_5_GATE);
    }

    private static List<EvalMetrics.CaseResult> hard(List<EvalMetrics.CaseResult> results) {
        return results.stream().filter(r -> r.tags().contains("hard")).toList();
    }
}
