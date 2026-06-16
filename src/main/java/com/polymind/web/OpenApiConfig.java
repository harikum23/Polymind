package com.polymind.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc / Swagger configuration. Serves a self-documenting OpenAPI 3 spec at
 * {@code /v3/api-docs} and an interactive console at {@code /swagger-ui.html}.
 *
 * <p>Two groups: {@code public} (the OpenAI-compatible {@code /v1/*} surface) and
 * {@code admin} ({@code /v1/admin/*}). A Bearer API-key scheme is declared so the UI can
 * authorize and call live endpoints.
 */
@Configuration
public class OpenApiConfig {

    static final String BEARER_SCHEME = "ApiKeyAuth";

    @Bean
    public OpenAPI polymindOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Polymind")
                        .version("0.1.0")
                        .description("""
                                Self-hosted smart LLM-router. OpenAI-compatible API with smart per-task
                                model routing (chat/code/math/reasoning), an optional knowledge (RAG)
                                layer, and first-class web search.

                                Polymind-specific extensions (OpenAI clients ignore unknown fields):
                                - `model`: concrete id (force) | category alias (`chat`/`code`/`math`/`reasoning`) | `auto`
                                - `metadata.task`: optional explicit task hint
                                - `metadata.knowledge_pack`: optional RAG pack name
                                - `metadata.web_search`: `true` to ground the answer in live search results
                                - `metadata.force`: `true` to forbid any routing fallback
                                """)
                        .license(new License().name("Apache-2.0")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("API-Key")
                                .description("Polymind API key, e.g. `Authorization: Bearer pmk-...`")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder()
                .group("public")
                .pathsToMatch("/v1/**")
                .pathsToExclude("/v1/admin/**")
                .build();
    }

    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group("admin")
                .pathsToMatch("/v1/admin/**")
                .build();
    }
}
