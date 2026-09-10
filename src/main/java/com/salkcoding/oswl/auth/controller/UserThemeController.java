package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.controller.spec.UserThemeControllerSpec;
import com.salkcoding.oswl.auth.dto.UserThemeRequest;
import com.salkcoding.oswl.auth.enums.UserThemeMode;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.UserThemeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/my")
@RequiredArgsConstructor
public class UserThemeController implements UserThemeControllerSpec {

    private final UserThemeService userThemeService;

    @GetMapping("/theme")
    public ResponseEntity<Map<String, String>> getTheme(
            @AuthenticationPrincipal OswlUserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("theme", UserThemeMode.LIGHT.name()));
        }
        UserThemeMode mode = userThemeService.getTheme(principal.getUserId());
        return ResponseEntity.ok(Map.of("theme", mode.name()));
    }

    @PostMapping("/theme")
    public ResponseEntity<Map<String, String>> updateTheme(
            @AuthenticationPrincipal OswlUserPrincipal principal,
            @RequestBody @Valid UserThemeRequest request) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required."));
        }
        userThemeService.updateTheme(principal, request.getTheme());
        return ResponseEntity.ok(Map.of("theme", request.getTheme().name()));
    }
}
