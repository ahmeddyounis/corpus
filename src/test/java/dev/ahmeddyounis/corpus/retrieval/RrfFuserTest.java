package dev.ahmeddyounis.corpus.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class RrfFuserTest {

    private final RrfFuser fuser = new RrfFuser();

    @Test
    void itemPresentInBothListsOutranksSingleListLeaders() {
        LinkedHashMap<String, Double> fused = fuser.fuse(60,
                List.of("a", "b", "c"),
                List.of("d", "b", "e"));

        // b: 1/62 + 1/62 ≈ 0.0323 beats a and d at 1/61 ≈ 0.0164.
        assertThat(fused.keySet().iterator().next()).isEqualTo("b");
        assertThat(fused.get("b")).isCloseTo(2.0 / 62, offset(1e-9));
        assertThat(fused.get("a")).isCloseTo(1.0 / 61, offset(1e-9));
    }

    @Test
    void preservesRankOrderWithinASingleList() {
        LinkedHashMap<String, Double> fused = fuser.fuse(60, List.of("first", "second", "third"), List.of());

        assertThat(fused.keySet()).containsExactly("first", "second", "third");
    }

    @Test
    void emptyInputsProduceEmptyResult() {
        assertThat(fuser.fuse(60, List.<String>of(), List.of())).isEmpty();
    }
}
