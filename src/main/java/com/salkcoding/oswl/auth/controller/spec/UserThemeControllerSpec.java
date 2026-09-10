package com.salkcoding.oswl.auth.controller.spec;

import com.salkcoding.oswl.auth.dto.UserThemeRequest;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@Tag(name = "My Account", description = "Self-service account operations for the signed-in user.")
public interface UserThemeControllerSpec {

    @Operation(summary = "Get current UI theme",
        description = "Returns the authenticated user's saved theme preference (LIGHT, DARK, or SYSTEM).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Theme preference",
            content = @Content(examples = @ExampleObject(value = "{ \"theme\": \"SYSTEM\" }"))),
        @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content)
    })
    ResponseEntity<Map<String, String>> getTheme(
        @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal
    );

    @Operation(summary = "Update UI theme",
        description = "Persists the user's UI theme preference. Use SYSTEM to follow the OS/browser setting.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Theme updated",
            content = @Content(examples = @ExampleObject(value = "{ \"theme\": \"DARK\" }"))),
        @ApiResponse(responseCode = "400", description = "Invalid theme value", content = @Content),
        @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content)
    })
    ResponseEntity<Map<String, String>> updateTheme(
        @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal,
        @Valid @RequestBody UserThemeRequest request
    );
}
