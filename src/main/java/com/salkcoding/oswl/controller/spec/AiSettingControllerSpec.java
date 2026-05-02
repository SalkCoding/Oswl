package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.dto.api.AiSettingResponse;
import com.salkcoding.oswl.dto.api.AiSettingUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;

@Tag(name = "AI Settings", description = "Configure the AI provider (OpenAI / Anthropic / Gemini / Local LLM) used for automated CVE and license risk summarisation.")
public interface AiSettingControllerSpec {

    @Operation(
        summary = "Get current active AI provider",
        description = "Returns the currently active AI setting. The `apiKey` field is masked. Returns a message object if no provider is configured."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Active AI setting (or unconfigured message)",
            content = @Content(schema = @Schema(implementation = AiSettingResponse.class),
                examples = {
                    @ExampleObject(name = "configured", value = """
                        {
                          "provider": "OPENAI",
                          "modelName": "gpt-4o-mini",
                          "baseUrl": null,
                          "apiKey": "sk-p...t3bz",
                          "active": true
                        }
                        """),
                    @ExampleObject(name = "unconfigured", value = """
                        { "message": "No AI provider configured" }
                        """)
                }))
    })
    ResponseEntity<AiSettingResponse> getCurrent();

    @Operation(
        summary = "Create or update an AI provider setting",
        description = """
            Upserts the configuration for the specified provider.
            Set `"activate": true` to immediately switch the active provider.

            Supported providers:
            - `OPENAI` — uses `https://api.openai.com/v1/chat/completions`
            - `ANTHROPIC` — uses `https://api.anthropic.com/v1/messages`
            - `GEMINI` — OpenAI-compatible endpoint (set `baseUrl` to Gemini's OpenAI-compat URL)
            - `LOCAL` — Ollama or any OpenAI-compatible local LLM (set `baseUrl`, e.g. `http://localhost:11434/v1`)
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Setting saved",
            content = @Content(schema = @Schema(implementation = AiSettingResponse.class),
                examples = @ExampleObject(value = """
                    {
                      "provider": "LOCAL",
                      "modelName": "llama3",
                      "baseUrl": "http://localhost:11434/v1",
                      "apiKey": null,
                      "active": true
                    }
                    """))),
        @ApiResponse(responseCode = "400", description = "provider field is required", content = @Content)
    })
    ResponseEntity<AiSettingResponse> upsert(
        @RequestBody(
            description = "AI provider configuration. `apiKey` may be omitted for LOCAL provider.",
            required = true,
            content = @Content(
                schema = @Schema(implementation = AiSettingUpdateRequest.class),
                examples = {
                    @ExampleObject(name = "openai", summary = "OpenAI GPT-4o", value = """
                        {
                          "provider": "OPENAI",
                          "apiKey": "sk-proj-...",
                          "modelName": "gpt-4o",
                          "activate": true
                        }
                        """),
                    @ExampleObject(name = "local", summary = "Local Ollama (llama3)", value = """
                        {
                          "provider": "LOCAL",
                          "modelName": "llama3",
                          "baseUrl": "http://localhost:11434/v1",
                          "activate": true
                        }
                        """),
                    @ExampleObject(name = "anthropic", summary = "Anthropic Claude", value = """
                        {
                          "provider": "ANTHROPIC",
                          "apiKey": "sk-ant-...",
                          "modelName": "claude-3-5-sonnet-20241022",
                          "activate": false
                        }
                        """)
                }
            )
        )
        @Valid @org.springframework.web.bind.annotation.RequestBody AiSettingUpdateRequest request
    );

    @Operation(
        summary = "Switch active AI provider",
        description = "Deactivates the current provider and activates the given one. The provider must already have a saved configuration — call `PUT /api/settings/ai` first."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Provider switched",
            content = @Content(schema = @Schema(implementation = AiSettingResponse.class),
                examples = @ExampleObject(value = """
                    {
                      "provider": "ANTHROPIC",
                      "modelName": "claude-3-5-sonnet-20241022",
                      "baseUrl": null,
                      "apiKey": "sk-a...3bcz",
                      "active": true
                    }
                    """))),
        @ApiResponse(responseCode = "400", description = "No saved configuration for the requested provider", content = @Content)
    })
    ResponseEntity<AiSettingResponse> activate(
        @Parameter(
            description = "Provider to activate",
            schema = @Schema(implementation = AiProvider.class),
            example = "ANTHROPIC",
            required = true
        )
        @PathVariable AiProvider provider
    );
}
