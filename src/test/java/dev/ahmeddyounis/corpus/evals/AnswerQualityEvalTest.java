package dev.ahmeddyounis.corpus.evals;

import dev.ahmeddyounis.corpus.chat.ChatService;
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
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Nightly answer-quality evals: real RAG answers over the golden set, scored by
 * an LLM judge for faithfulness (grounded in the retrieved context) and answer
 * relevance. Needs ANTHROPIC_API_KEY; skipped entirely without it. Run with
 * {@code ./gradlew nightlyEval}.
 */
@Tag("nightly")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@ActiveProfiles({"test", "nightly"})
@TestPropertySource(properties = {
        "spring.ai.model.chat=anthropic",
        "spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY:}",
        "spring.ai.anthropic.chat.model=${CORPUS_EVAL_MODEL:claude-haiku-4-5}"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnswerQualityEvalTest extends AbstractIntegrationTest {

    private static final double FAITHFULNESS_GATE = 0.80;
    private static final double RELEVANCE_GATE = 0.80;
    private static final Pattern SCORE = Pattern.compile("([01](?:\\.\\d+)?)");

    @Autowired
    private IngestionService ingestionService;
    @Autowired
    private RetrievalService retrievalService;
    @Autowired
    private ChatService chatService;
    @Autowired
    private ChatModel chatModel;
    @Autowired
    private UserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID evalUserId;
    private ChatClient judge;

    record CaseScore(String id, double faithfulness, double relevance, String answer) {
    }

    @BeforeAll
    void ingestSampleCorpus() throws Exception {
        judge = ChatClient.builder(chatModel).build();
        UserAccount evalUser = users.findByUsername("judge-eval-user")
                .orElseGet(() -> users.save(UserAccount.create("judge-eval-user", passwordEncoder.encode("x"))));
        evalUserId = evalUser.id();

        Set<String> present = ingestionService.list(evalUserId).stream()
                .filter(d -> d.status() == DocumentStatus.READY)
                .map(d -> d.filename())
                .collect(Collectors.toSet());
        Resource[] samples = new PathMatchingResourcePatternResolver().getResources("classpath:samples/*.md");
        for (Resource sample : samples) {
            if (!present.contains(sample.getFilename())) {
                ingestionService.upload(evalUserId, sample.getFilename(), "text/markdown",
                        sample.getContentAsByteArray());
            }
        }
        int expected = samples.length;
        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            long ready = ingestionService.list(evalUserId).stream()
                    .filter(d -> d.status() == DocumentStatus.READY).count();
            assertThat(ready).isEqualTo(expected);
        });
    }

    @Test
    void answersAreFaithfulAndRelevant() throws Exception {
        List<GoldenSet.GoldenCase> cases = GoldenSet.load();
        List<CaseScore> scores = new ArrayList<>();

        for (GoldenSet.GoldenCase goldenCase : cases) {
            String context = retrievalService.search(evalUserId, goldenCase.question(), 5, null).stream()
                    .map(ScoredChunk::content)
                    .collect(Collectors.joining("\n---\n"));
            ChatService.SyncAnswer answer = chatService.answerSync(evalUserId, goldenCase.question(), 5);

            double faithfulness = judge0to1("""
                    You are grading whether an answer is faithful to its source context.
                    Faithful means: every factual claim in the answer is supported by the context; \
                    nothing material is invented. Citation markers like [1] are fine.

                    CONTEXT:
                    %s

                    ANSWER:
                    %s

                    Respond with ONLY a number between 0.0 and 1.0 (1.0 = fully faithful).
                    """.formatted(context, answer.answer()));

            double relevance = judge0to1("""
                    You are grading whether an answer addresses the question asked.

                    QUESTION: %s

                    REFERENCE (a known-good answer): %s

                    ANSWER TO GRADE:
                    %s

                    Respond with ONLY a number between 0.0 and 1.0 (1.0 = fully addresses the question).
                    """.formatted(goldenCase.question(), goldenCase.referenceAnswer(), answer.answer()));

            scores.add(new CaseScore(goldenCase.id(), faithfulness, relevance, answer.answer()));
        }

        double meanFaithfulness = scores.stream().mapToDouble(CaseScore::faithfulness).average().orElse(0);
        double meanRelevance = scores.stream().mapToDouble(CaseScore::relevance).average().orElse(0);

        String report = report(scores, meanFaithfulness, meanRelevance);
        System.out.println(report);
        Path out = Path.of("build/reports/evals");
        Files.createDirectories(out);
        Files.writeString(out.resolve("answer-quality.json"), json(scores, meanFaithfulness, meanRelevance));

        assertThat(meanFaithfulness).as("faithfulness regression%n%s", report)
                .isGreaterThanOrEqualTo(FAITHFULNESS_GATE);
        assertThat(meanRelevance).as("relevance regression%n%s", report)
                .isGreaterThanOrEqualTo(RELEVANCE_GATE);
    }

    private double judge0to1(String prompt) {
        String verdict = judge.prompt().user(prompt).call().content();
        Matcher m = SCORE.matcher(verdict == null ? "" : verdict.strip());
        if (!m.find()) {
            return 0;
        }
        return Math.clamp(Double.parseDouble(m.group(1)), 0.0, 1.0);
    }

    private static String report(List<CaseScore> scores, double meanFaithfulness, double meanRelevance) {
        StringBuilder sb = new StringBuilder("\n===== Answer quality report =====\n");
        sb.append(String.format("%-28s %-14s %s%n", "case", "faithfulness", "relevance"));
        for (CaseScore s : scores) {
            sb.append(String.format("%-28s %-14.2f %.2f%n", s.id(), s.faithfulness(), s.relevance()));
        }
        sb.append(String.format(Locale.ROOT,
                "mean faithfulness = %.3f (gate %.2f)   mean relevance = %.3f (gate %.2f)%n",
                meanFaithfulness, FAITHFULNESS_GATE, meanRelevance, RELEVANCE_GATE));
        sb.append("=================================\n");
        return sb.toString();
    }

    private static String json(List<CaseScore> scores, double meanFaithfulness, double meanRelevance) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "{%n  \"meanFaithfulness\": %.4f,%n  \"meanRelevance\": %.4f,%n  \"cases\": [%n",
                meanFaithfulness, meanRelevance));
        for (int i = 0; i < scores.size(); i++) {
            CaseScore s = scores.get(i);
            sb.append(String.format(Locale.ROOT,
                    "    {\"id\": \"%s\", \"faithfulness\": %.4f, \"relevance\": %.4f}%s%n",
                    s.id(), s.faithfulness(), s.relevance(), i < scores.size() - 1 ? "," : ""));
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }
}
