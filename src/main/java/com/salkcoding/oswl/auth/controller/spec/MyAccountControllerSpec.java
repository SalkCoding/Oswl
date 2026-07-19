package com.salkcoding.oswl.auth.controller.spec;

import com.salkcoding.oswl.auth.dto.DeleteAccountRequest;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@Tag(name = "My Account", description = "Self-service account operations for the signed-in user.")
public interface MyAccountControllerSpec {

    @Operation(summary = "Delete own account",
        description = """
            Permanently deletes the current user's account after verifying the password.
            The session is terminated on success. The target account is always the authenticated
            principal — there is no path or body parameter that could target another account.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Account deleted — session terminated",
            content = @Content(examples = @ExampleObject(value = "{ \"message\": \"Your account has been permanently deleted.\", \"redirectUrl\": \"/login\" }"))),
        @ApiResponse(responseCode = "400", description = "Password missing/incorrect, or the account cannot be deleted", content = @Content),
        @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content)
    })
    ResponseEntity<Map<String, String>> deleteAccount(
        @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal,
        @Valid @RequestBody DeleteAccountRequest request,
        @Parameter(hidden = true) HttpServletRequest httpRequest,
        @Parameter(hidden = true) HttpServletResponse httpResponse
    );
}
