package dev.ahmeddyounis.corpus.security;

import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "corpus.rate-limit.rpm=3")
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CorpusRateLimitProperties rateLimitProperties;

    @Test
    void requestsBeyondBudgetGet429WithRetryAfter() throws Exception {
        assertThat(rateLimitProperties.rpm()).isEqualTo(3);
        String token = demoToken();

        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me"))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            for (int i = 0; i < 3; i++) {
                HttpResponse<String> ok = http.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(ok.statusCode()).as("request %d within budget", i).isEqualTo(200);
                assertThat(ok.headers().firstValue("X-RateLimit-Remaining"))
                        .as("rate limit filter should have run (request %d)", i)
                        .contains(String.valueOf(2 - i));
            }

            HttpResponse<String> limited = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(limited.statusCode()).isEqualTo(429);
            assertThat(limited.headers().firstValue("Retry-After")).isPresent();
            assertThat(limited.body()).contains("rate_limit_exceeded");
        }
    }
}
