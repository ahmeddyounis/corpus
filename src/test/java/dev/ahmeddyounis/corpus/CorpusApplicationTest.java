package dev.ahmeddyounis.corpus;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusApplicationTest {

    @Test
    void applicationClassIsBootAnnotated() {
        assertThat(CorpusApplication.class.isAnnotationPresent(SpringBootApplication.class)).isTrue();
    }
}
