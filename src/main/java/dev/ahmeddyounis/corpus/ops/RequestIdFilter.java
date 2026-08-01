package dev.ahmeddyounis.corpus.ops;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Stamps every request with a correlation id, echoed back so a client can quote it
 * in a bug report and an operator can find the exact request in the logs.
 *
 * <p>Complements trace ids rather than duplicating them: a request id exists even
 * when a trace is not sampled.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_CLIENT_IP = "clientIp";

    private static final int MAX_SUPPLIED_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = sanitize(request.getHeader(HEADER));
        try {
            MDC.put(MDC_REQUEST_ID, requestId);
            // Logged once per request so a rate-limit incident is diagnosable; this is
            // the address after any forwarded-header resolution.
            MDC.put(MDC_CLIENT_IP, request.getRemoteAddr());
            response.setHeader(HEADER, requestId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_CLIENT_IP);
        }
    }

    /** Client-supplied ids are echoed into logs and headers, so bound and clean them. */
    private static String sanitize(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String trimmed = supplied.strip();
        if (trimmed.length() > MAX_SUPPLIED_LENGTH) {
            trimmed = trimmed.substring(0, MAX_SUPPLIED_LENGTH);
        }
        return trimmed.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
