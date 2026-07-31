package dev.ahmeddyounis.corpus.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP access policy. When {@code anonymousUser} is set (local/keyless profiles),
 * unauthenticated MCP calls act as that account — instant Claude Desktop connect.
 * When blank (cloud), MCP tool calls require a bearer token.
 */
@ConfigurationProperties(prefix = "corpus.mcp")
public record CorpusMcpProperties(String anonymousUser) {

    public CorpusMcpProperties {
        if (anonymousUser == null) {
            anonymousUser = "";
        }
    }
}
