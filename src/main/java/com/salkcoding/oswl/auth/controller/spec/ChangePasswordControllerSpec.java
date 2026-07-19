package com.salkcoding.oswl.auth.controller.spec;

import com.salkcoding.oswl.auth.dto.ChangePasswordRequest;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@Tag(name = "Forced Password Change", description = """
        Mandatory password change for admin-invited users (`mustChangePassword` flag).
        Requires an authenticated session — access to other URLs is blocked until the password has been changed.
        """)
public interface ChangePasswordControllerSpec {

    @Operation(summary = "Complete the forced password change",
        description = """
            Replaces the temporary password with a user-chosen one after validating the current password.
            The SecurityContext is refreshed and the session ID is rotated on success, clearing the
            `mustChangePassword` restriction without a re-login.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Password changed",
            content = @Content(examples = @ExampleObject(value = "{ \"redirectUrl\": \"/projects\" }"))),
        @ApiResponse(responseCode = "400", description = "Validation error — new password too short, passwords do not match, current password incorrect, or new password same as current", content = @Content),
        @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content)
    })
    ResponseEntity<Map<String, String>> changePassword(
        @RequestBody ChangePasswordRequest req,
        @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal,
        @Parameter(hidden = true) HttpServletRequest request
    );
}
