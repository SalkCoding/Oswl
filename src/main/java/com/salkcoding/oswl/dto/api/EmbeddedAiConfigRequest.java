package com.salkcoding.oswl.dto.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Schema(description = "Embedded AI (llama.cpp sidecar) configuration. A null field keeps the current value; a blank string clears the override.")
@Getter
@Setter
public class EmbeddedAiConfigRequest {

    @Schema(description = "Root directory with llama/ runtime and model/Qwen, model/Gemma weights. Null = keep current; blank = clear override (use oswl.ai.embedded.dir default).",
            example = "C:\\tools\\embedded-ai")
    private String dir;

    @Schema(description = "Preferred .gguf model file name. Null = keep current; blank = clear override (use built-in preference order). Takes effect on next start.",
            example = "Qwen3.5-2B-Q4_K_M.gguf")
    private String model;
}
