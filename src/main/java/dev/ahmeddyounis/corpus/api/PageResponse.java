package dev.ahmeddyounis.corpus.api;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Explicit pagination envelope. Serializing Spring's {@code Page} directly emits an
 * unstable, framework-internal shape that Boot itself warns about, so the API
 * contract is stated here instead.
 */
public record PageResponse<T>(List<T> items, int page, int size, long total, boolean hasNext) {

    public static <S, T> PageResponse<T> of(Page<S> page, java.util.function.Function<S, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.hasNext());
    }
}
