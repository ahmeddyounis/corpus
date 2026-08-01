package dev.ahmeddyounis.corpus.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CorpusRateLimitProperties rateLimitProperties,
                                            RateLimitBuckets rateLimitBuckets) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/token",
                                "/actuator/health", "/actuator/health/**", "/actuator/prometheus",
                                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                "/error")
                        .permitAll()
                        // Streamable-HTTP MCP uses GET/POST/DELETE on /mcp. A bearer token is
                        // authenticated when present; anonymous access is mapped to the demo
                        // user only when corpus.mcp.anonymous-user is set (local profile).
                        .requestMatchers("/mcp", "/mcp/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .addFilterAfter(new RateLimitFilter(rateLimitProperties, rateLimitBuckets),
                        BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** The development fallback shipped in application.yml — public knowledge by definition. */
    static final String DEV_DEFAULT_SECRET = "corpus-local-dev-secret-0123456789-abcdefghijklmnop";

    @Bean
    JwtDecoder jwtDecoder(CorpusSecurityProperties properties, Environment environment) {
        return NimbusJwtDecoder.withSecretKey(secretKey(properties, environment))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    JwtEncoder jwtEncoder(CorpusSecurityProperties properties, Environment environment) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties, environment)));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static SecretKeySpec secretKey(CorpusSecurityProperties properties, Environment environment) {
        byte[] secret = properties.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            // HS256 requires >= 256-bit keys; failing here beats a per-request
            // KeyLengthException 500 the first time a token is issued.
            throw new IllegalStateException(
                    "corpus.security.jwt.secret must be at least 32 bytes (got " + secret.length
                            + "). Set CORPUS_JWT_SECRET to a random string of 32+ characters.");
        }
        if (environment.acceptsProfiles(Profiles.of("cloud"))
                && DEV_DEFAULT_SECRET.equals(properties.jwt().secret())) {
            // The dev fallback lives in a public repository; with it, anyone can mint
            // valid tokens for any user id. Never allow it to serve the cloud profile.
            throw new IllegalStateException(
                    "The cloud profile must not run with the published development JWT secret. "
                            + "Set CORPUS_JWT_SECRET to a private random string of 32+ characters.");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }
}
