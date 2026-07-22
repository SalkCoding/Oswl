package com.salkcoding.oswl.auth.controller.spec;

import com.salkcoding.oswl.auth.dto.MailTestRequest;
import com.salkcoding.oswl.auth.dto.SecuritySettingResponse;
import com.salkcoding.oswl.auth.dto.SecuritySettingUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@Tag(name = "Settings — Security", description = "Instance security settings — SMTP mail, two-factor authentication, and password policy. Requires the SETTINGS_SECURITY_MANAGE permission or the SYSTEM_ADMIN role.")
public interface SecuritySettingsControllerSpec {

    @Operation(summary = "Get security settings",
        description = "Returns the current security settings. The SMTP password is never included in the response.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Security settings",
            content = @Content(schema = @Schema(implementation = SecuritySettingResponse.class))),
        @ApiResponse(responseCode = "403", description = "Missing SETTINGS_SECURITY_MANAGE permission and not a SYSTEM_ADMIN", content = @Content)
    })
    ResponseEntity<SecuritySettingResponse> get();

    @Operation(summary = "Update security settings",
        description = """
            Updates mail settings and/or the 2FA mode. Both blocks are optional and the UI
            sends them in separate save calls: `{ mailMode, mail }` and `{ twoFaMode }`.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Updated settings",
            content = @Content(schema = @Schema(implementation = SecuritySettingResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid settings value", content = @Content),
        @ApiResponse(responseCode = "403", description = "Missing SETTINGS_SECURITY_MANAGE permission and not a SYSTEM_ADMIN", content = @Content)
    })
    ResponseEntity<SecuritySettingResponse> update(
        @RequestBody SecuritySettingUpdateRequest req
    );

    @Operation(summary = "Test SMTP connection",
        description = "Sends a test email using the supplied SMTP settings without persisting them.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Connection successful",
            content = @Content(examples = @ExampleObject(value = "{ \"message\": \"Connection successful.\" }"))),
        @ApiResponse(responseCode = "400", description = "SMTP connection failed",
            content = @Content(examples = @ExampleObject(value = "{ \"message\": \"<SMTP error detail>\" }"))),
        @ApiResponse(responseCode = "403", description = "Missing SETTINGS_SECURITY_MANAGE permission and not a SYSTEM_ADMIN", content = @Content),
        @ApiResponse(responseCode = "500", description = "Unexpected error", content = @Content)
    })
    ResponseEntity<Map<String, Object>> testMail(
        @RequestBody MailTestRequest req
    );
}
