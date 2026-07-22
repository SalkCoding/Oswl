package com.salkcoding.oswl.auth.controller.spec;

import com.salkcoding.oswl.auth.dto.ChangePasswordRequest;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@Tag(name = "My Account — Password", description = """
        Voluntary (user-initiated) password change for the signed-in user.
        When 2FA is enabled, the step-up email OTP challenge must be completed before the password change is accepted.
        """)
public interface MyChangePasswordControllerSpec {

    @Operation(summary = "Verify step-up OTP",
        description = """
            Verifies the 6-digit code sent to the user's email as a step-up challenge for the password change.
            Only available while an OTP challenge is pending in the session.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Code verified — password change is now unlocked",
            content = @Content(examples = @ExampleObject(value = "{ \"redirectUrl\": \"/my/change-password\" }"))),
        @ApiResponse(responseCode = "400", description = "No pending OTP challenge, or the code is invalid/expired", content = @Content),
        @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content)
    })
    ResponseEntity<Map<String, String>> verifyOtp(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "OTP code payload",
            required = true,
            content = @Content(
                schema = @Schema(type = "object"),
                examples = @ExampleObject(value = "{ \"code\": \"123456\" }")
            )
        )
        @RequestBody Map<String, Object> body,
        @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal,
        @Parameter(hidden = true) HttpServletRequest request
    );

    @Operation(summary = "Resend step-up OTP",
        description = "Regenerates and resends the step-up OTP code. Rate-limited to one resend per 60 seconds.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Code resent",
            content = @Content(examples = @ExampleObject(value = "{ \"message\": \"The code has been resent.\" }"))),
        @ApiResponse(responseCode = "400", description = "No pending OTP challenge", content = @Content),
        @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
        @ApiResponse(responseCode = "429", description = "Resend requested before the 60-second cooldown elapsed", content = @Content)
    })
    ResponseEntity<Map<String, String>> resendOtp(
        @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal,
        @Parameter(hidden = true) HttpServletRequest request
    );

    @Operation(summary = "Change password",
        description = """
            Changes the current user's password after validating the current password.
            When 2FA is enabled, the step-up OTP challenge must be verified first — otherwise `403` is returned.
            The session ID is rotated on success.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Password changed",
            content = @Content(examples = @ExampleObject(value = "{ \"redirectUrl\": \"/projects\" }"))),
        @ApiResponse(responseCode = "400", description = "Validation error — new password too short, passwords do not match, current password incorrect, or new password same as current", content = @Content),
        @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
        @ApiResponse(responseCode = "403", description = "Step-up OTP verification required (2FA enabled)", content = @Content)
    })
    ResponseEntity<Map<String, String>> changePassword(
        @RequestBody ChangePasswordRequest req,
        @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal,
        @Parameter(hidden = true) HttpServletRequest request
    );
}
