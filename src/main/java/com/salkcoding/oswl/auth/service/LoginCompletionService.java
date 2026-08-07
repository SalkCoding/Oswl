package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Records post-login side effects once authentication is fully complete. */
@Service
@RequiredArgsConstructor
public class LoginCompletionService {

    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final OnboardingService onboardingService;

    @Transactional
    public void recordSuccessfulLogin(String email) {
        userRepository.updateLastLoginAt(email, LocalDateTime.now());
        auditLogService.log("AUTH.LOGIN_SUCCESS", "AUTH", null, null, null);
    }

    /**
     * Where a fully-authenticated user should land — password change first if required, then
     * (for the system admin only, until dismissed) the onboarding wizard, else the projects page.
     * Onboarding never gates non-admin users; only the admin who ran /setup is expected to walk
     * through org-wide configuration (SSO/invites, repo connection, policy defaults, notifications).
     */
    @Transactional(readOnly = true)
    public String resolvePostLoginDestination(OswlUserPrincipal principal) {
        if (principal.isMustChangePassword()) {
            return "/change-password";
        }
        if (principal.isSystemAdmin() && !onboardingService.isCompleted()) {
            return "/onboarding";
        }
        return "/projects";
    }
}
