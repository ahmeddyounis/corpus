package dev.ahmeddyounis.corpus.evals;

import java.util.List;

/** Retrieval metrics over golden-set outcomes. Ranks are 1-based; 0 means miss. */
public final class EvalMetrics {

    public record CaseResult(String id, String question, List<String> expected,
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
                    retrievedFilenames, rank);
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

    public static String table(List<CaseResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-28s %-6s %s%n", "case", "rank", "top-5 sources"));
        for (CaseResult r : results) {
            sb.append(String.format("%-28s %-6s %s%n",
                    r.id(),
                    r.firstRelevantRank() > 0 ? "#" + r.firstRelevantRank() : "MISS",
                    String.join(", ", r.retrievedFilenames().stream().distinct().toList())));
        }
        return sb.toString();
    }
}
