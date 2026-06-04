package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.dto.api.AiFeedbackRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestBody;

@Tag(name = "AI Feedback", description = "User feedback on AI-generated summaries (helpful / not helpful).")
public interface AiFeedbackControllerSpec {

    @Operation(summary = "Submit AI summary feedback",
            description = """
                    Records whether an AI summary was helpful. `targetType` and `targetKey` identify the subject
                    (e.g. `CVE` + `projectId:componentId:cveDbId`).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Feedback saved", content = @Content),
            @ApiResponse(responseCode = "400", description = "Validation error", content = @Content)
    })
    ResponseEntity<Void> submit(
            @Valid @RequestBody AiFeedbackRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal
    );
}
