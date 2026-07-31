package dev.ahmeddyounis.corpus.retrieval;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Reciprocal Rank Fusion: {@code score(d) = Σ over result lists of 1 / (k + rank_i(d))}
 * with 1-based ranks. Items surfacing in both lists accumulate both terms, which is
 * the whole point — agreement between the keyword and vector legs beats either alone.
 */
@Component
public class RrfFuser {

    @SafeVarargs
    public final <T> LinkedHashMap<T, Double> fuse(int k, List<T>... rankedLists) {
        Map<T, Double> scores = new HashMap<>();
        for (List<T> list : rankedLists) {
            for (int i = 0; i < list.size(); i++) {
                scores.merge(list.get(i), 1.0 / (k + i + 1), Double::sum);
            }
        }
        LinkedHashMap<T, Double> sorted = new LinkedHashMap<>();
        scores.entrySet().stream()
                .sorted(Map.Entry.<T, Double>comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }
}
