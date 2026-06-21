package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.dto.DeleteAccountRequest;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.AccountDeletionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class MyAccountController {

    private final AccountDeletionService accountDeletionService;

    /**
     * Self-service account deletion. The user id is taken only from the authenticated principal —
     * there is no path or body parameter that could target another account.
     */
    @PostMapping("/api/my/delete-account")
    public ResponseEntity<Map<String, String>> deleteAccount(
            @AuthenticationPrincipal OswlUserPrincipal principal,
            @RequestBody @Valid DeleteAccountRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required."));
        }
        try {
            accountDeletionService.deleteOwnAccount(principal.getUserId(), request.getPassword());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        new SecurityContextLogoutHandler().logout(httpRequest, httpResponse, null);
        return ResponseEntity.ok(Map.of(
                "message", "Your account has been permanently deleted.",
                "redirectUrl", "/login"));
    }
}
