package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.exception.ForbiddenException;
import com.salkcoding.oswl.exception.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Resolves the submitter principal for {@code POST /api/scan}.
 * STANDARD API keys require submitter email + password; MACHINE keys use the bound user.
 */
@Service
@RequiredArgsConstructor
public class ScanSubmitAuthService {

    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final ProjectAccessService projectAccessService;

    public record AuthenticatedSubmitter(OswlUserPrincipal principal, String actorEmail) {}

    public AuthenticatedSubmitter authenticate(
            ApiKey apiKey,
            Long projectId,
            String submitterEmail,
            String submitterPassword) {

        if (apiKey != null && apiKey.isMachineToken()) {
            return authenticateMachineKey(apiKey, projectId);
        }
        return authenticateStandardKey(projectId, submitterEmail, submitterPassword);
    }

    private AuthenticatedSubmitter authenticateMachineKey(ApiKey apiKey, Long projectId) {
        if (apiKey.getBoundUser() == null) {
            throw new UnauthorizedException("Machine token is not bound to a user.");
        }
        String email = apiKey.getBoundUser().getEmail();
        OswlUserPrincipal principal = loadPrincipal(email);
        assertScanSubmitAllowed(projectId, principal, email);
        return new AuthenticatedSubmitter(principal, email);
    }

    private AuthenticatedSubmitter authenticateStandardKey(
            Long projectId, String submitterEmail, String submitterPassword) {
        if (submitterEmail == null || submitterEmail.isBlank()) {
            throw new UnauthorizedException("submitterEmail is required");
        }
        if (submitterPassword == null || submitterPassword.isBlank()) {
            throw new UnauthorizedException("submitterPassword is required");
        }
        OswlUserPrincipal principal = loadPrincipal(submitterEmail);
        if (!passwordEncoder.matches(submitterPassword, principal.getPassword())) {
            throw new UnauthorizedException("Invalid credentials");
        }
        assertScanSubmitAllowed(projectId, principal, submitterEmail);
        return new AuthenticatedSubmitter(principal, submitterEmail);
    }

    private OswlUserPrincipal loadPrincipal(String email) {
        try {
            return (OswlUserPrincipal) userDetailsService.loadUserByUsername(email);
        } catch (UsernameNotFoundException e) {
            throw new UnauthorizedException("Invalid credentials");
        }
    }

    private void assertScanSubmitAllowed(Long projectId, OswlUserPrincipal principal, String email) {
        if (!principal.hasPermission(Permission.SCAN_SUBMIT)) {
            throw new ForbiddenException("User does not have SCAN_SUBMIT permission");
        }
        try {
            projectAccessService.assertCanSubmitScan(projectId, principal.getUserId());
        } catch (ForbiddenException e) {
            throw e;
        }
    }
}
