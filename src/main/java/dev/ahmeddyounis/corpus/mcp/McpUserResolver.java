package dev.ahmeddyounis.corpus.mcp;

import dev.ahmeddyounis.corpus.security.CurrentUser;
import dev.ahmeddyounis.corpus.security.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves the acting user for MCP tool calls: bearer JWT when present,
 * otherwise the configured anonymous account (local profile), otherwise reject.
 */
@Component
public class McpUserResolver {

    private final CurrentUser currentUser;
    private final UserRepository users;
    private final CorpusMcpProperties properties;

    public McpUserResolver(CurrentUser currentUser, UserRepository users, CorpusMcpProperties properties) {
        this.currentUser = currentUser;
        this.users = users;
        this.properties = properties;
    }

    public UUID resolveUserId() {
        return currentUser.idIfPresent().orElseGet(() -> {
            if (properties.anonymousUser().isBlank()) {
                throw new IllegalStateException(
                        "MCP access requires a bearer token in this profile (corpus.mcp.anonymous-user is unset)");
            }
            return users.findByUsername(properties.anonymousUser())
                    .orElseThrow(() -> new IllegalStateException(
                            "Configured corpus.mcp.anonymous-user '" + properties.anonymousUser() + "' does not exist"))
                    .id();
        });
    }
}
