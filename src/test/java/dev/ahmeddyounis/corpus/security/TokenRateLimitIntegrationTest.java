package dev.ahmeddyounis.corpus.security;

import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** The anonymous token endpoint is throttled per client IP (credential-stuffing brake). */
@TestPropertySource(properties = {"corpus.rate-limit.token-rpm=3", "corpus.rate-limit.rpm=500"})
class TokenRateLimitIntegrationTest extends AbstractIntegrationTest {

    @Test
    void tokenEndpointIsRateLimitedPerIp() throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpRequest attempt = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/token"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"username\":\"demo\",\"password\":\"wrong-password\"}"))
                    .build();

            for (int i = 0; i < 3; i++) {
                HttpResponse<String> response = http.send(attempt, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as("attempt %d within budget", i).isEqualTo(401);
            }

            HttpResponse<String> limited = http.send(attempt, HttpResponse.BodyHandlers.ofString());
            assertThat(limited.statusCode()).isEqualTo(429);
            assertThat(limited.headers().firstValue("Retry-After")).isPresent();
        }
    }
}
