package dev.ahmeddyounis.corpus.evals;

import dev.ahmeddyounis.corpus.chat.CorpusResponseCacheProperties;
import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calibrates the semantic response cache threshold from data instead of taste.
 *
 * <p>A response cache has two ways to be wrong, and they pull in opposite
 * directions. Too low a threshold answers one question with another question's
 * answer — a correctness bug that looks like a working feature. Too high a
 * threshold never hits, so the cache is dead weight. The only honest way to pick
 * the number is to measure both edges and check there is daylight between them.
 *
 * <p>This test scores every pair of distinct golden questions (the "must not
 * collide" set) against hand-written paraphrases of golden questions (the "must
 * collide" set), and reports the margin.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SemanticCacheEvalTest extends AbstractIntegrationTest {

    /**
     * Paraphrases a user would reasonably expect to be treated as the same
     * question. Deliberately not trivial restatements: word order, synonyms, and
     * phrasing all change.
     */
    private record Paraphrase(String original, String rephrased) {
    }

    private static final List<Paraphrase> PARAPHRASES = List.of(
            new Paraphrase("What is Reciprocal Rank Fusion and why does Corpus use it?",
                    "Why does Corpus use reciprocal rank fusion?"),
            new Paraphrase("How does Corpus chunk documents before embedding them?",
                    "What is the chunking strategy used before embedding?"),
            new Paraphrase("Which vector index does Corpus use in pgvector?",
                    "What pgvector index type does Corpus rely on?"),
            new Paraphrase("How does Corpus stream chat responses to the client?",
                    "In what way are chat responses streamed back to clients?"),
            new Paraphrase("What tools does the Corpus MCP server expose?",
                    "Which tools are available from the Corpus MCP server?"));

    /**
     * How far the threshold must sit above the most similar unrelated pair. Not a
     * round number for its own sake: measured at 0.173, kept at 0.10 so an
     * embedding-model change that compresses the similarity scale fails here
     * rather than in production.
     */
    private static final double FALSE_HIT_SAFETY_MARGIN = 0.10;

    @Autowired
    private EmbeddingModel embeddingModel;
    @Autowired
    private CorpusResponseCacheProperties properties;

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    @Test
    void thresholdSeparatesParaphrasesFromUnrelatedQuestions() {
        List<GoldenSet.GoldenCase> cases = GoldenSet.load();
        List<float[]> vectors = cases.stream().map(c -> embeddingModel.embed(c.question())).toList();

        // Worst case for false hits: the two most similar questions that are not
        // the same question.
        double closestUnrelated = 0;
        String closestPair = "";
        int compared = 0;
        for (int i = 0; i < cases.size(); i++) {
            for (int j = i + 1; j < cases.size(); j++) {
                // Cases that share an expected source may legitimately be near
                // duplicates of each other; the dangerous collisions are between
                // questions about different documents.
                if (!java.util.Collections.disjoint(cases.get(i).expectedSources(),
                        cases.get(j).expectedSources())) {
                    continue;
                }
                compared++;
                double similarity = cosine(vectors.get(i), vectors.get(j));
                if (similarity > closestUnrelated) {
                    closestUnrelated = similarity;
                    closestPair = cases.get(i).id() + " vs " + cases.get(j).id();
                }
            }
        }

        // Worst case for false misses: the paraphrase the model considers least
        // similar to its original.
        double weakestParaphrase = 1.0;
        String weakestPair = "";
        for (Paraphrase paraphrase : PARAPHRASES) {
            double similarity = cosine(embeddingModel.embed(paraphrase.original()),
                    embeddingModel.embed(paraphrase.rephrased()));
            if (similarity < weakestParaphrase) {
                weakestParaphrase = similarity;
                weakestPair = paraphrase.rephrased();
            }
        }

        double threshold = properties.similarityThreshold();
        String report = """

                ===== Semantic cache threshold =====
                configured threshold          %.4f
                closest unrelated pair        %.4f  (%s)   [%d pairs compared]
                weakest paraphrase            %.4f  (%s)
                margin below threshold        %+.4f
                margin above threshold        %+.4f
                ====================================
                """.formatted(threshold, closestUnrelated, closestPair, compared,
                weakestParaphrase, weakestPair,
                threshold - closestUnrelated, weakestParaphrase - threshold);
        System.out.println(report.toLowerCase(Locale.ROOT).isEmpty() ? "" : report);

        // The two failure modes are not symmetric. A false hit answers one
        // question with another's answer - a correctness bug wearing the costume
        // of a working feature - so that side gets a stated minimum margin. A
        // false miss only costs an LLM call, so ordering is enough there.
        assertThat(threshold - closestUnrelated)
                .as("too little headroom over the closest unrelated pair: the cache could "
                        + "answer a distinct question with the wrong answer%n%s", report)
                .isGreaterThanOrEqualTo(FALSE_HIT_SAFETY_MARGIN);
        assertThat(weakestParaphrase)
                .as("a genuine paraphrase falls below the threshold, so the cache never "
                        + "hits and is dead weight%n%s", report)
                .isGreaterThanOrEqualTo(threshold);
    }

    /**
     * Normalization must not be so aggressive that it collapses distinct
     * questions into one exact-match key — the exact phase does no similarity
     * check at all, so a collision there is unconditional.
     */
    @Test
    void exactMatchNormalizationOnlyFoldsCaseAndWhitespace() {
        List<GoldenSet.GoldenCase> cases = GoldenSet.load();

        List<String> normalized = cases.stream()
                .map(c -> dev.ahmeddyounis.corpus.chat.ResponseCache.normalize(c.question()))
                .toList();

        assertThat(normalized).doesNotHaveDuplicates();
        assertThat(dev.ahmeddyounis.corpus.chat.ResponseCache.normalize("  What   IS rrf? "))
                .isEqualTo("what is rrf?");
    }
}
