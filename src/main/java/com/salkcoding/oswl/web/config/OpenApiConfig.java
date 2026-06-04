package com.salkcoding.oswl.web.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("local")
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "BearerAuth";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("OSWL API")
                        .description("""
                                OSWL (Open Source Watch List) backend API.

                                ## Authentication

                                | Endpoint group | Auth method |
                                |---|---|
                                | `POST /api/auth`, `GET /api/scan/ping`, `POST /api/scan`, `GET /api/scan/{id}/status` | **Bearer token** — `Authorization: Bearer oswl_<token>` |
                                | All other `/api/**` endpoints | **Session cookie** (JSESSIONID) obtained via `POST /login` + OTP |

                                CLI tokens are issued via `POST /api/projects/{projectId}/keys` or `POST /api/admin/cli-keys`.

                                ## Live progress (SSE)

                                | Stream | Event | Payload |
                                |---|---|---|
                                | `GET /api/quick-import/job/{jobId}/stream` | `job-update` | `QuickImportJobStatus` JSON |
                                | `GET /projects/scan-status/stream?ids=` | `scan-update` | scan status JSON |

                                Controller OpenAPI annotations live in `controller/spec/*Spec.java` interfaces.
                                """)
                        .version("0.0.1")
                        .contact(new Contact()
                                .name("salkcoding")
                                .url("https://github.com/salkcoding")))
                // BearerAuth is applied only to CLI Scan endpoints via @SecurityRequirement in ScanControllerSpec.
                // Management API endpoints use session-based authentication.
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("oswl_<token>")
                                .description("API key issued via POST /api/projects/{projectId}/keys")));
    }
}
