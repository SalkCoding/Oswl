package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.dto.api.WebhookDeliveryDto;
import com.salkcoding.oswl.dto.api.WebhookSettingResponse;
import com.salkcoding.oswl.dto.api.WebhookSettingUpdateRequest;
import com.salkcoding.oswl.dto.api.WebhookTestRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@Tag(name = "Webhook Settings", description = "Configure Slack/Teams incoming webhook notifications and view delivery history.")
public interface WebhookSettingControllerSpec {

    @Operation(summary = "Get webhook settings",
            description = "Returns the configured provider, event toggles, and whether a webhook URL is stored. The URL itself is never returned.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Webhook settings",
                    content = @Content(schema = @Schema(implementation = WebhookSettingResponse.class)))
    })
    ResponseEntity<WebhookSettingResponse> getSettings();

    @Operation(summary = "Save webhook settings",
            description = "Upserts the single-row webhook settings. The URL is encrypted at rest. A blank URL keeps the existing URL.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Settings saved"),
            @ApiResponse(responseCode = "400", description = "Invalid URL or validation error", content = @Content)
    })
    ResponseEntity<Void> saveSettings(
            @RequestBody(description = "Webhook settings", required = true,
                    content = @Content(schema = @Schema(implementation = WebhookSettingUpdateRequest.class)))
            @Valid @org.springframework.web.bind.annotation.RequestBody WebhookSettingUpdateRequest request
    );

    @Operation(summary = "Test webhook URL",
            description = "Sends a sample notification to the provided URL without saving it. Useful for validating connectivity before storing credentials.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Test result",
                    content = @Content(schema = @Schema(implementation = Map.class)))
    })
    ResponseEntity<Map<String, Object>> testWebhook(
            @RequestBody(description = "URL to test", required = true,
                    content = @Content(schema = @Schema(implementation = WebhookTestRequest.class)))
            @Valid @org.springframework.web.bind.annotation.RequestBody WebhookTestRequest request
    );

    @Operation(summary = "List recent webhook deliveries",
            description = "Returns the most recent delivery attempts (success or failure) for the failure-history UI.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of deliveries",
                    content = @Content(schema = @Schema(implementation = WebhookDeliveryDto.class)))
    })
    ResponseEntity<List<WebhookDeliveryDto>> getDeliveries(
            @Parameter(description = "Filter by event type")
            @RequestParam(required = false) String eventType,
            @Parameter(description = "Filter by status (SUCCESS or FAILED)")
            @RequestParam(required = false) String status,
            @Parameter(description = "Maximum number of rows", example = "50")
            @RequestParam(defaultValue = "50") int limit
    );
}
