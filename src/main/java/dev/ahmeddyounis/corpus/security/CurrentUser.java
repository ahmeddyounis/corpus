package dev.ahmeddyounis.corpus.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Resolves the calling user's id from the JWT on the security context. */
@Component
public class CurrentUser {

    public UUID id() {
        return idIfPresent().orElseThrow(() -> new IllegalStateException("No authenticated user"));
    }

    public Optional<UUID> idIfPresent() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return Optional.of(UUID.fromString(jwt.getToken().getSubject()));
        }
        return Optional.empty();
    }
}
