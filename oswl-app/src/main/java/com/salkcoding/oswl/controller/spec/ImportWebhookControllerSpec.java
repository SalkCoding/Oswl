package com.salkcoding.oswl.controller.spec;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

import java.util.Map;

@Tag(name = "VCS Webhook", description = "Inbound push webhooks for automatic re-scan (per-project secret, no session auth).")
public interface ImportWebhookControllerSpec {

    @Operation(
            summary = "Handle VCS push webhook",
            description = "Accepts GitHub, GitLab, or Bitbucket Cloud push payloads. Verified via per-project webhook secret."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Accepted and scan queued",
                    content = @Content(schema = @Schema(example = """
                            { "accepted": true, "message": "Scan queued", "jobId": "abc-123" }
                            """))),
            @ApiResponse(responseCode = "400", description = "Rejected or ignored event", content = @Content)
    })
    ResponseEntity<Map<String, Object>> handleWebhook(String body, HttpServletRequest request);
}
