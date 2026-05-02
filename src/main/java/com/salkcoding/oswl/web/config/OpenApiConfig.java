package com.salkcoding.oswl.web.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "BearerAuth";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("OSWL API")
                        .description("""
                                OSWL (Open Source Watch List) backend API.

                                **CLI Endpoints** (`/api/scan/**`) require a Bearer token issued via \
                                `POST /api/projects/{projectId}/keys`.
                                Include it as `Authorization: Bearer oswl_<token>` in every CLI request.

                                **Management Endpoints** (`/api/projects/**`, `/api/settings/**`) \
                                are for the OSWL web UI and do not require authentication in the \
                                current development build.
                                """)
                        .version("0.0.1")
                        .contact(new Contact()
                                .name("salkcoding")
                                .url("https://github.com/salkcoding")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("oswl_<token>")
                                .description("API key issued via POST /api/projects/{projectId}/keys")));
    }
}
