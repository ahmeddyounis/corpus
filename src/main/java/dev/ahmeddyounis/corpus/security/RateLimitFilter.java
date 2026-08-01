package dev.ahmeddyounis.corpus.security;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Token-bucket limits over {@code /api/**} and {@code /mcp}: per authenticated user
 * when a JWT is present, per client IP on the anonymous token endpoint
 * (credential-stuffing brake) and on anonymous MCP calls, which reach the same
 * costly retrieval and model paths as the REST API.
 *
 * <p>Client identity comes from {@code getRemoteAddr()}, which is only meaningful
 * behind a proxy when {@code server.forward-headers-strategy} is set — the deployed
 * profiles do so. Without it every anonymous caller would share one bucket.
 *
 * <p>Instantiated directly by {@link SecurityConfig} — deliberately not a bean, so
 * Boot does not also register it as a plain servlet filter (which would double-count).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String TOKEN_ENDPOINT = "/api/auth/token";

    private final RateLimitBuckets buckets;
    private final int rpm;
    private final int tokenRpm;

    public RateLimitFilter(CorpusRateLimitProperties properties, RateLimitBuckets buckets) {
        this.buckets = buckets;
        this.rpm = properties.rpm();
        this.tokenRpm = properties.tokenRpm();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !(uri.startsWith("/api/") || uri.equals("/mcp") || uri.startsWith("/mcp/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (TOKEN_ENDPOINT.equals(uri)) {
            if (!consume(buckets.resolve("token:" + request.getRemoteAddr(), tokenRpm), response)) {
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            UUID userId = UUID.fromString(jwt.getToken().getSubject());
            if (!consume(buckets.resolve("user:" + userId, rpm), response)) {
                return;
            }
        } else if (uri.equals("/mcp") || uri.startsWith("/mcp/")) {
            // Anonymous MCP (local profiles map it to the demo user) still pays
            // the standard per-caller budget, keyed by client IP.
            if (!consume(buckets.resolve("mcp:" + request.getRemoteAddr(), rpm), response)) {
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    /** Consumes one token; on rejection writes a committed 429 and returns false. */
    private static boolean consume(Bucket bucket, HttpServletResponse response) throws IOException {
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            return true;
        }
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
        return false;
    }
}
