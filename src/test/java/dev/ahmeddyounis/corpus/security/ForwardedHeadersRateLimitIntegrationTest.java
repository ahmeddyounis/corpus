package dev.ahmeddyounis.corpus.security;

import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behind a proxy, rate-limit buckets must key on the real client — otherwise every
 * anonymous caller shares one bucket and the service throttles itself globally.
 * Forged headers from an untrusted peer must be ignored, or the fix becomes a bypass.
 */
class ForwardedHeadersRateLimitIntegrationTest {

    private static HttpResponse<String> tokenAttempt(int port, String forwardedFor) throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/token"))
                    .header("Content-Type", "application/json")
                    .header("X-Forwarded-For", forwardedFor)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"username\":\"demo\",\"password\":\"wrong-password\"}"))
                    .build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "server.forward-headers-strategy=NATIVE",
            "server.tomcat.remoteip.remote-ip-header=x-forwarded-for",
            "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1|0:0:0:0:0:0:0:1",
            "corpus.rate-limit.token-rpm=2"})
    class WhenTheProxyIsTrusted extends AbstractIntegrationTest {

        @Test
        void eachForwardedClientGetsItsOwnBudget() throws Exception {
            assertThat(tokenAttempt(port, "203.0.113.10").statusCode()).isEqualTo(401);
            assertThat(tokenAttempt(port, "203.0.113.10").statusCode()).isEqualTo(401);
            assertThat(tokenAttempt(port, "203.0.113.10").statusCode())
                    .as("third attempt from the same client exhausts its budget")
                    .isEqualTo(429);

            assertThat(tokenAttempt(port, "203.0.113.20").statusCode())
                    .as("a different client must not inherit the first client's exhausted bucket")
                    .isEqualTo(401);
        }
    }

    @Nested
    @TestPropertySource(properties = {
            "server.forward-headers-strategy=NATIVE",
            "server.tomcat.remoteip.remote-ip-header=x-forwarded-for",
            // The loopback peer these tests connect from is deliberately NOT trusted.
            "server.tomcat.remoteip.internal-proxies=10\\.99\\.99\\.99",
            "corpus.rate-limit.token-rpm=2"})
    class WhenThePeerIsNotATrustedProxy extends AbstractIntegrationTest {

        @Test
        void forgedForwardedHeadersAreIgnored() throws Exception {
            assertThat(tokenAttempt(port, "203.0.113.30").statusCode()).isEqualTo(401);
            assertThat(tokenAttempt(port, "203.0.113.31").statusCode()).isEqualTo(401);

            assertThat(tokenAttempt(port, "203.0.113.32").statusCode())
                    .as("rotating a forged X-Forwarded-For must not win extra budget")
                    .isEqualTo(429);
        }
    }
}
