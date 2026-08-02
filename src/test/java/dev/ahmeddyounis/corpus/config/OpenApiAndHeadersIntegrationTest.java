package dev.ahmeddyounis.corpus.config;

import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiAndHeadersIntegrationTest extends AbstractIntegrationTest {

    @Test
    @SuppressWarnings("unchecked")
    void apiDocsDeclareTheBearerSchemeSoSwaggerCanAuthenticate() {
        ResponseEntity<Map<String, Object>> docs = restClient().get().uri("/v3/api-docs")
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {
                });

        assertThat(docs.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = docs.getBody();

        Map<String, Object> info = (Map<String, Object>) body.get("info");
        assertThat(info).containsEntry("title", "Corpus API").containsKey("version");

        Map<String, Object> components = (Map<String, Object>) body.get("components");
        Map<String, Object> schemes = (Map<String, Object>) components.get("securitySchemes");
        assertThat(schemes)
                .as("without this Swagger UI has no Authorize button and every secured call 401s")
                .containsKey("bearer-jwt");
        Map<String, Object> bearer = (Map<String, Object>) schemes.get("bearer-jwt");
        assertThat(bearer).containsEntry("scheme", "bearer").containsEntry("bearerFormat", "JWT");
    }

    @Test
    void securityHeadersArePresentOnApiResponses() {
        ResponseEntity<String> response = restClient().get().uri("/api/auth/me")
                .header("Authorization", "Bearer " + demoToken())
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).contains("frame-ancestors 'none'");
    }

    @Test
    void corsIsDisabledByDefault() {
        ResponseEntity<String> preflight = restClient().method(HttpMethod.OPTIONS).uri("/api/search")
                .header("Origin", "https://not-configured.example")
                .header("Access-Control-Request-Method", "POST")
                .retrieve()
                .toEntity(String.class);

        assertThat(preflight.getHeaders().getFirst("Access-Control-Allow-Origin")).isNull();
    }

    @Nested
    @TestPropertySource(properties = "corpus.security.cors.allowed-origins=https://allowed.example")
    class WhenAnOriginIsConfigured extends AbstractIntegrationTest {

        @Test
        void allowedOriginIsEchoedAndOthersAreNot() {
            ResponseEntity<String> allowed = restClient().method(HttpMethod.OPTIONS).uri("/api/search")
                    .header("Origin", "https://allowed.example")
                    .header("Access-Control-Request-Method", "POST")
                    .retrieve()
                    .toEntity(String.class);
            assertThat(allowed.getHeaders().getFirst("Access-Control-Allow-Origin"))
                    .isEqualTo("https://allowed.example");
            assertThat(allowed.getHeaders().getFirst("Access-Control-Expose-Headers"))
                    .contains("X-Request-Id");

            ResponseEntity<String> denied = restClient().method(HttpMethod.OPTIONS).uri("/api/search")
                    .header("Origin", "https://evil.example")
                    .header("Access-Control-Request-Method", "POST")
                    .retrieve()
                    .toEntity(String.class);
            assertThat(denied.getHeaders().getFirst("Access-Control-Allow-Origin")).isNull();
        }
    }
}
