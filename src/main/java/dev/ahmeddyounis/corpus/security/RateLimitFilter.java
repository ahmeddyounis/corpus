package dev.ahmeddyounis.corpus.security;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-user token bucket over {@code /api/**} (the anonymous token endpoint is exempt).
 * Instantiated directly by {@link SecurityConfig} — deliberately not a bean, so Boot
 * does not also register it as a plain servlet filter (which would double-count).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final ConcurrentHashMap<UUID, Bucket> buckets = new ConcurrentHashMap<>();
    private final int rpm;

    public RateLimitFilter(CorpusRateLimitProperties properties) {
        this.rpm = properties.rpm();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/") || uri.equals("/api/auth/token");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            UUID userId = UUID.fromString(jwt.getToken().getSubject());
            Bucket bucket = buckets.computeIfAbsent(userId, id -> Bucket.builder()
                    .addLimit(limit -> limit.capacity(rpm).refillGreedy(rpm, Duration.ofMinutes(1)))
                    .build());
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                byte[] body = "{\"error\":\"rate_limit_exceeded\"}".getBytes(StandardCharsets.UTF_8);
                response.setStatus(429);
                response.setHeader("Retry-After",
                        String.valueOf(TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1));
                response.setContentType("application/json");
                response.setContentLength(body.length);
                response.getOutputStream().write(body);
                // Commit immediately: an uncommitted error response can leave the
                // connection in a state where clients retry the request.
                response.flushBuffer();
                return;
            }
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
        }
        filterChain.doFilter(request, response);
    }
}
