package com.salkcoding.oswl.auth.controller.spec;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@Tag(name = "Login OTP", description = """
        Two-factor OTP verification during sign-in.
        These endpoints are part of the unauthenticated login flow (`permitAll`) and operate on the
        pending-authentication HTTP session — no signed-in session or bearer token is required.
        """)
public interface OtpVerifyControllerSpec {

    @Operation(summary = "Verify login OTP",
        description = """
            Verifies the 6-digit code sent by email during the 2FA login flow.
            On success the session is promoted to a fully authenticated state and a `redirectUrl` is returned
            (`/change-password` when a forced password change is pending, otherwise `/projects`).
            Pass `trustDevice: true` to skip OTP on this device for 30 days.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Code verified — login completed",
            content = @Content(examples = @ExampleObject(value = "{ \"redirectUrl\": \"/projects\" }"))),
        @ApiResponse(responseCode = "400", description = "The code is invalid or has expired", content = @Content),
        @ApiResponse(responseCode = "401", description = "No pending authentication, or the session was displaced by a concurrent login", content = @Content),
        @ApiResponse(responseCode = "423", description = "Account locked after too many failed attempts", content = @Content)
    })
    ResponseEntity<Map<String, String>> verifyOtp(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "OTP code payload",
            required = true,
            content = @Content(
                schema = @Schema(type = "object"),
                examples = @ExampleObject(value = "{ \"code\": \"123456\", \"trustDevice\": false }")
            )
        )
        @RequestBody Map<String, Object> body,
        @Parameter(hidden = true) HttpServletRequest request,
        @Parameter(hidden = true) HttpServletResponse response
    );

    @Operation(summary = "Resend login OTP",
        description = """
            Regenerates and resends the login OTP code. Rate-limited to one resend per 60 seconds.
            The response includes `mailFailed: true` when the resend email could not be delivered.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Code resent",
            content = @Content(examples = @ExampleObject(value = "{ \"message\": \"The code has been resent.\", \"mailFailed\": false }"))),
        @ApiResponse(responseCode = "401", description = "No pending authentication", content = @Content),
        @ApiResponse(responseCode = "429", description = "Resend requested before the 60-second cooldown elapsed", content = @Content)
    })
    ResponseEntity<?> resendOtp(
        @Parameter(hidden = true) HttpServletRequest request
    );
}
