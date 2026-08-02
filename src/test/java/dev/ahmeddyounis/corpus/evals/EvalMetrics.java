package dev.ahmeddyounis.corpus.evals;

import java.util.List;
import java.util.Locale;

/**
 * Retrieval metrics over golden-set outcomes. Ranks are 1-based; 0 means miss.
 *
 * <p>Reports more than one metric on purpose: recall alone hides a system that
 * finds the answer but buries it, and MRR alone hides a system that ranks its one
 * hit perfectly while missing everything else.
 */
public final class EvalMetrics {

    public record CaseResult(String id, String question, List<String> expected, List<String> tags,
                             List<String> retrievedFilenames, int firstRelevantRank) {

        public static CaseResult of(GoldenSet.GoldenCase goldenCase, List<String> retrievedFilenames) {
            int rank = 0;
            for (int i = 0; i < retrievedFilenames.size(); i++) {
                if (goldenCase.expectedSources().contains(retrievedFilenames.get(i))) {
                    rank = i + 1;
                    break;
                }
            }
            return new CaseResult(goldenCase.id(), goldenCase.question(), goldenCase.expectedSources(),
                    goldenCase.tags(), retrievedFilenames, rank);
        }

        boolean relevantAt(int zeroBasedPosition) {
            return zeroBasedPosition < retrievedFilenames.size()
                    && expected.contains(retrievedFilenames.get(zeroBasedPosition));
        }
    }

    /** Aggregate scores for one configuration, so two runs can be compared directly. */
    public record Summary(String label, int cases, double recallAt1, double recallAt3, double recallAt5,
                          double mrr, double precisionAt5, double ndcgAt5) {

        public static Summary of(String label, List<CaseResult> results) {
            return new Summary(label, results.size(),
                    recallAtK(results, 1), recallAtK(results, 3), recallAtK(results, 5),
                    meanReciprocalRank(results), precisionAtK(results, 5), ndcgAtK(results, 5));
        }

        public String describe() {
            return String.format(Locale.ROOT,
                    "%-16s cases=%d  recall@1=%.3f  recall@3=%.3f  recall@5=%.3f  MRR=%.3f  P@5=%.3f  nDCG@5=%.3f",
                    label, cases, recallAt1, recallAt3, recallAt5, mrr, precisionAt5, ndcgAt5);
        }
    }

    private EvalMetrics() {
    }

    public static double recallAtK(List<CaseResult> results, int k) {
        if (results.isEmpty()) {
            return 0;
        }
        long hits = results.stream().filter(r -> r.firstRelevantRank() > 0 && r.firstRelevantRank() <= k).count();
        return (double) hits / results.size();
    }

    public static double meanReciprocalRank(List<CaseResult> results) {
        if (results.isEmpty()) {
            return 0;
        }
        return results.stream()
                .mapToDouble(r -> r.firstRelevantRank() > 0 ? 1.0 / r.firstRelevantRank() : 0.0)
                .average()
                .orElse(0);
    }

    /** Share of the returned k that were relevant, averaged over cases. */
    public static double precisionAtK(List<CaseResult> results, int k) {
        if (results.isEmpty()) {
            return 0;
        }
        return results.stream()
                .mapToDouble(r -> {
                    int considered = Math.min(k, r.retrievedFilenames().size());
                    if (considered == 0) {
                        return 0;
                    }
                    long relevant = java.util.stream.IntStream.range(0, considered)
                            .filter(r::relevantAt)
                            .count();
                    return (double) relevant / considered;
                })
                .average()
                .orElse(0);
    }

    /**
     * Binary-gain nDCG@k. Sensitive to ordering within the window, which recall is
     * blind to — so a reranker that only permutes results still moves this.
     */
    public static double ndcgAtK(List<CaseResult> results, int k) {
        if (results.isEmpty()) {
            return 0;
        }
        return results.stream()
                .mapToDouble(r -> {
                    double dcg = 0;
                    int relevantSeen = 0;
                    for (int i = 0; i < Math.min(k, r.retrievedFilenames().size()); i++) {
                        if (r.relevantAt(i)) {
                            dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
                            relevantSeen++;
                        }
                    }
                    if (relevantSeen == 0) {
                        return 0;
                    }
                    double idcg = 0;
                    for (int i = 0; i < Math.min(k, relevantSeen); i++) {
                        idcg += 1.0 / (Math.log(i + 2) / Math.log(2));
                    }
                    return idcg == 0 ? 0 : dcg / idcg;
                })
                .average()
                .orElse(0);
    }

    public static String table(List<CaseResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-30s %-8s %-6s %s%n", "case", "tags", "rank", "top sources"));
        for (CaseResult r : results) {
            sb.append(String.format("%-30s %-8s %-6s %s%n",
                    r.id(),
                    r.tags().isEmpty() ? "-" : String.join(",", r.tags()),
                    r.firstRelevantRank() > 0 ? "#" + r.firstRelevantRank() : "MISS",
                    String.join(", ", r.retrievedFilenames().stream().distinct().limit(4).toList())));
        }
        return sb.toString();
    }

    /** Machine-readable report so before/after runs are auditable as a CI artifact. */
    public static String json(List<Summary> summaries) {
        StringBuilder sb = new StringBuilder("{\n  \"summaries\": [\n");
        for (int i = 0; i < summaries.size(); i++) {
            Summary s = summaries.get(i);
            sb.append(String.format(Locale.ROOT,
                    "    {\"label\": \"%s\", \"cases\": %d, \"recallAt1\": %.4f, \"recallAt3\": %.4f, "
                            + "\"recallAt5\": %.4f, \"mrr\": %.4f, \"precisionAt5\": %.4f, \"ndcgAt5\": %.4f}%s%n",
                    s.label(), s.cases(), s.recallAt1(), s.recallAt3(), s.recallAt5(),
                    s.mrr(), s.precisionAt5(), s.ndcgAt5(), i < summaries.size() - 1 ? "," : ""));
        }
        return sb.append("  ]\n}\n").toString();
    }
}
