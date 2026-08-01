package dev.ahmeddyounis.corpus.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearer-jwt";

    /**
     * Declares the bearer scheme so Swagger UI shows an Authorize button. Without it
     * the UI can send no credentials at all, so every secured endpoint answers 401
     * and the interactive docs are effectively unusable.
     */
    @Bean
    OpenAPI corpusOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Corpus API")
                        .version("1.0")
                        .description("""
                                RAG over your documents: hybrid retrieval (keyword + vector fused with \
                                Reciprocal Rank Fusion), SSE chat with inline citations, and the same \
                                capabilities exposed to AI agents over MCP at /mcp.

                                Obtain a token from POST /api/auth/token, then use Authorize.""")
                        .license(new License().name("MIT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT issued by POST /api/auth/token")));
    }
}
