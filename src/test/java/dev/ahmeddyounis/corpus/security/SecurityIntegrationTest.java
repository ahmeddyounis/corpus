package dev.ahmeddyounis.corpus.security;

import dev.ahmeddyounis.corpus.support.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityIntegrationTest extends AbstractIntegrationTest {

    @Test
    void issuesTokenForValidCredentials() {
        ResponseEntity<Map<String, Object>> response = restClient().post().uri("/api/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "demo", "password", "demo"))
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsKeys("token", "expiresInSeconds");
    }

    @Test
    void rejectsInvalidCredentials() {
        ResponseEntity<String> response = restClient().post().uri("/api/auth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("username", "demo", "password", "nope"))
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void protectedEndpointsRequireToken() {
        ResponseEntity<String> response = restClient().get().uri("/api/auth/me")
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void bearerTokenGrantsAccess() {
        ResponseEntity<Map<String, Object>> response = restClient().get().uri("/api/auth/me")
                .header("Authorization", "Bearer " + demoToken())
                .retrieve()
                .toEntity(new ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("username")).isEqualTo("demo");
        assertThat(response.getBody().get("id")).isNotNull();
    }
}
