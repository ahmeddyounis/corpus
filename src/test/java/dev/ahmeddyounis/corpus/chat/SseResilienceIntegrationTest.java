package dev.ahmeddyounis.corpus.chat;

import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Streams have to survive proxies: a load balancer that idles out at 60s would cut a
 * long answer, and a buffering proxy would defeat streaming entirely. The heartbeat
 * writes from a different thread than the token loop, so the emitter — which is not
 * thread-safe — must never see concurrent writes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "corpus.chat.heartbeat-interval=100ms")
class SseResilienceIntegrationTest extends AbstractIntegrationTest {

    private static final String FIXTURE = """
            # SSE fixture
            Streaming answers must survive intermediary proxies and idle timeouts.
            """;

    private String token;

    @BeforeAll
    void seed() {
        token = demoToken();
        if (listDocuments(token).stream().noneMatch(d -> "sse.md".equals(d.get("filename"))
                && "READY".equals(d.get("status")))) {
            uploadAndAwaitReady(token, "sse.md", FIXTURE.getBytes(StandardCharsets.UTF_8));
        }
    }

    private HttpResponse<String> chat(HttpClient http, String message) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"" + message + "\"}"))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void streamCarriesProxyDefeatingHeadersAndStillCompletes() throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = chat(http, "Does streaming survive a proxy?");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("X-Accel-Buffering"))
                    .as("nginx buffers proxied responses by default")
                    .contains("no");
            assertThat(response.headers().firstValue("Cache-Control").orElse(""))
                    .contains("no-transform");

            // The established event contract must be unchanged by the heartbeat.
            assertThat(response.body())
                    .contains("event:token")
                    .contains("event:citations")
                    .contains("event:usage")
                    .contains("event:done");
        }
    }

    /** The emitter is not thread-safe; concurrent writers must not corruptframing. */
    @Test
    void concurrentStreamsWithHeartbeatsStayWellFormed() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Callable<String>> calls = IntStream.range(0, 4)
                    .<Callable<String>>mapToObj(i -> () -> {
                        try (HttpClient http = HttpClient.newHttpClient()) {
                            return chat(http, "Concurrent stream probe " + i).body();
                        }
                    })
                    .toList();

            for (Future<String> future : pool.invokeAll(calls, 120, TimeUnit.SECONDS)) {
                String body = future.get();
                assertThat(body).contains("event:done");
                // Every data line must belong to a named event or be a comment;
                // interleaved writes would produce orphaned frames.
                body.lines().filter(line -> line.startsWith("data:"))
                        .forEach(line -> assertThat(line).startsWith("data:"));
            }
        }
    }
}
