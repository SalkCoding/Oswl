package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.dto.api.AiPromptsResponse;
import com.salkcoding.oswl.dto.api.AiSettingResponse;
import com.salkcoding.oswl.dto.api.AiSettingUpdateRequest;
import com.salkcoding.oswl.dto.api.AiTestConnectionRequest;
import com.salkcoding.oswl.dto.api.AiUsageEventDto;
import com.salkcoding.oswl.dto.api.AiUsageStatsResponse;
import com.salkcoding.oswl.dto.api.EmbeddedAiConfigRequest;
import java.util.Map;
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
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

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

    @Operation(summary = "Get editable prompt templates",
            description = "Returns resolved prompt text for editable keys plus stored JSON overrides from preferences.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Prompt snapshot",
                    content = @Content(schema = @Schema(implementation = AiPromptsResponse.class)))
    })
    ResponseEntity<AiPromptsResponse> getPrompts();

    @Operation(summary = "Get AI usage statistics",
            description = "Returns today's token/cost totals, the daily call cap and per-day aggregates for the last 7 days, read from the daily aggregate table. The recent-call list is served separately by `GET /api/settings/ai/usage/events`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Usage statistics",
                    content = @Content(schema = @Schema(implementation = AiUsageStatsResponse.class)))
    })
    ResponseEntity<AiUsageStatsResponse> getUsageStats();

    @Operation(summary = "List recent AI call events",
            description = "Returns raw AI call events, newest first, as a page (default size 10, max 50). Only the most recent 100 events are retained (FIFO), so at most 10 pages of 10 exist.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of usage events", content = @Content)
    })
    ResponseEntity<Page<AiUsageEventDto>> getUsageEvents(
            @Parameter(description = "Zero-based page index", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (1-50)", example = "10")
            @RequestParam(defaultValue = "10") int size
    );

    @Operation(summary = "Run golden prompt regression tests",
            description = "Executes built-in fixture prompts against the active AI provider. Does not persist results.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Per-fixture pass/fail summary", content = @Content)
    })
    ResponseEntity<Map<String, Object>> runGoldenTests();

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

    @Operation(summary = "Deactivate the active AI provider",
            description = "Optional body may include preference fields to update before deactivation.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Provider deactivated", content = @Content)
    })
    ResponseEntity<Void> deactivate(
            @org.springframework.web.bind.annotation.RequestBody(required = false) AiSettingUpdateRequest request
    );

    @Operation(
        summary = "Test connection to an AI provider",
        description = "Sends a minimal ping to the specified provider using the supplied credentials. No data is persisted. If `apiKey` is omitted the stored (encrypted) key is used."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Test result",
            content = @Content(
                examples = {
                    @ExampleObject(name = "success", value = """
                        { "success": true, "message": "Connection successful!" }
                        """),
                    @ExampleObject(name = "failure", value = """
                        { "success": false, "message": "Connection failed. Check your API key and model name." }
                        """)
                })),
        @ApiResponse(responseCode = "400", description = "API key not configured and not provided", content = @Content)
    })
    ResponseEntity<Map<String, Object>> testConnection(
        @Valid @org.springframework.web.bind.annotation.RequestBody AiTestConnectionRequest request
    );

    @Operation(summary = "Embedded AI status",
            description = "Reports whether the sidecar binary and .gguf models are present, whether the server is running (and `external` if it wasn't started by this OsWL instance), and — while the default model download triggered by `POST .../embedded/start` is in flight — `downloading`/`downloadedBytes`/`downloadTotalBytes` for a progress UI.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Embedded AI status", content = @Content)
    })
    ResponseEntity<Map<String, Object>> embeddedStatus();

    @Operation(summary = "Start embedded AI",
            description = """
                Launches the llama.cpp llama-server sidecar and registers it as the active LOCAL provider.
                The optional `model` query parameter names a .gguf file from the sidecar directory to try first;
                when it fails to start (or is omitted) the persisted preference, built-in preference order
                (Qwen3 1.7B, then any remaining `.gguf`) are tried in turn (auto-fallback).
                The response status body includes `activeModel`, `fallbackUsed` and `lastError`.

                On a fresh install with no `.gguf` file present, this instead downloads the
                Apache-2.0-licensed Qwen3-1.7B model (verifying its SHA256) in the background and
                returns immediately with `success: true` — poll `GET /api/settings/ai/embedded` for
                `downloading`, `downloadedBytes`/`downloadTotalBytes`, and the eventual `running` or
                `lastError` outcome.
                """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sidecar started and LOCAL provider activated, or the default-model download started in the background", content = @Content),
            @ApiResponse(responseCode = "400", description = "Binary or model missing, or startup failed", content = @Content)
    })
    ResponseEntity<Map<String, Object>> startEmbedded(
            @Parameter(description = "Optional .gguf file name (from `availableModels`) to start with",
                    example = "qwen3-1.7b-q4_k_m.gguf")
            @RequestParam(required = false) String model
    );

    @Operation(summary = "Stop embedded AI",
            description = "Stops the llama.cpp sidecar and deactivates the LOCAL provider.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sidecar stopped", content = @Content)
    })
    ResponseEntity<Map<String, Object>> stopEmbedded();

    @Operation(summary = "Configure embedded AI",
            description = """
                Persists the embedded sidecar configuration overrides. Either field may be null (keep current
                value) or blank (clear the override and fall back to the yaml/env default).
                `dir` must point to an existing directory; the response status body reports `binaryFound`
                for it instead of hard-failing when llama-server(.exe) is absent. If the sidecar is running
                and the directory actually changes, the sidecar is stopped first (and the LOCAL provider
                deactivated) so the old directory's file locks are released. A changed `model` takes effect
                on the next start.
                """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Config saved; returns the embedded status body",
                    content = @Content),
            @ApiResponse(responseCode = "400", description = "Directory does not exist / is not a directory, or invalid model file name",
                    content = @Content)
    })
    ResponseEntity<Map<String, Object>> updateEmbeddedConfig(
            @RequestBody(
                    description = "Embedded config overrides. Null field = keep current; blank string = clear override.",
                    content = @Content(
                            schema = @Schema(implementation = EmbeddedAiConfigRequest.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "dir": "C:\\\\tools\\\\embedded-ai",
                                      "model": "qwen3-1.7b-q4_k_m.gguf"
                                    }
                                    """)
                    )
            )
            @org.springframework.web.bind.annotation.RequestBody(required = false) EmbeddedAiConfigRequest request
    );
}
