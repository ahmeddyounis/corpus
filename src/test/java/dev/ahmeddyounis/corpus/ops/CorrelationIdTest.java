package dev.ahmeddyounis.corpus.ops;

import jakarta.servlet.FilterChain;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class CorrelationIdTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesEchoesAndThenClearsTheRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/documents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInsideChain = new AtomicReference<>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(invocation -> {
            seenInsideChain.set(MDC.get(RequestIdFilter.MDC_REQUEST_ID));
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(request, response, chain);

        assertThat(seenInsideChain.get()).isNotBlank();
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(seenInsideChain.get());
        assertThat(MDC.get(RequestIdFilter.MDC_REQUEST_ID))
                .as("MDC must not leak to the next request on a pooled thread")
                .isNull();
    }

    @Test
    void preservesACleanClientSuppliedIdAndSanitizesAHostileOne() throws Exception {
        assertThat(idFor("client-supplied-123")).isEqualTo("client-supplied-123");

        String hostile = idFor("bad\nid\r with spaces");
        assertThat(hostile).doesNotContain("\n").doesNotContain("\r").doesNotContain(" ");
    }

    @Test
    void boundsAnOverlongSuppliedId() throws Exception {
        assertThat(idFor("x".repeat(500))).hasSizeLessThanOrEqualTo(64);
    }

    /**
     * The decisive one: ingestion and chat hand off to another thread immediately, so
     * without propagation every log line they emit would be unattributable.
     */
    @Test
    void mdcCrossesTheThreadHopOntoExecutors() throws Exception {
        MdcTaskDecorator decorator = new MdcTaskDecorator();
        MDC.put(RequestIdFilter.MDC_REQUEST_ID, "req-42");
        AtomicReference<String> onOtherThread = new AtomicReference<>();

        Runnable decorated = decorator.decorate(
                () -> onOtherThread.set(MDC.get(RequestIdFilter.MDC_REQUEST_ID)));
        Thread worker = new Thread(decorated);
        worker.start();
        worker.join();

        assertThat(onOtherThread.get()).isEqualTo("req-42");
        MDC.clear();
    }

    @Test
    void decoratedTaskRestoresTheWorkerThreadsPreviousContext() throws Exception {
        MdcTaskDecorator decorator = new MdcTaskDecorator();
        MDC.put(RequestIdFilter.MDC_REQUEST_ID, "submitter");
        Runnable decorated = decorator.decorate(() -> { });
        MDC.clear();

        AtomicReference<Map<String, String>> afterRun = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            decorated.run();
            afterRun.set(MDC.getCopyOfContextMap());
        });
        worker.start();
        worker.join();

        assertThat(afterRun.get()).as("the worker thread is left clean for its next task").isNull();
    }

    private String idFor(String supplied) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/documents");
        request.addHeader(RequestIdFilter.HEADER, supplied);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, mock(FilterChain.class));
        return response.getHeader(RequestIdFilter.HEADER);
    }
}
